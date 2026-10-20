package io.promptfuzzer.execution;

import io.promptfuzzer.core.TestCase;
import io.promptfuzzer.core.TestSuite;
import io.promptfuzzer.evaluation.Evaluation;
import io.promptfuzzer.evaluation.Evaluator;
import io.promptfuzzer.evaluation.Judge;
import io.promptfuzzer.llm.Budget;
import io.promptfuzzer.llm.ChatModel;
import io.promptfuzzer.llm.ChatRequest;
import io.promptfuzzer.llm.ChatResponse;
import io.promptfuzzer.llm.LlmException;
import io.promptfuzzer.llm.ResilientChatModel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Executes a suite against one or more targets. Each test input is sent as the user turn, with the task's
 * system prompt as the instructions, and evaluated as soon as it returns. Calls run concurrently on virtual
 * threads, bounded by a semaphore; rate limiting and retries live in the model decorators.
 *
 * <p>Results are emitted in completion order through the listener and returned in a stable order
 * (model, test, repeat) so reports are reproducible.
 */
public final class TestRunner {

    private final List<ChatModel> targets;
    private final Judge judge;
    private final Options options;

    /**
     * @param repeats         times each test is sent to each target (to measure consistency)
     * @param concurrency     maximum in-flight calls
     * @param maxOutputTokens output cap for target calls (thinking models spend part of it reasoning)
     */
    public record Options(int repeats, int concurrency, int maxOutputTokens) {
        public Options {
            if (repeats < 1 || repeats > 10) {
                throw new IllegalArgumentException("repeats must be between 1 and 10");
            }
            if (concurrency < 1 || concurrency > 32) {
                throw new IllegalArgumentException("concurrency must be between 1 and 32");
            }
            if (maxOutputTokens < 64) {
                throw new IllegalArgumentException("maxOutputTokens must be at least 64");
            }
        }
    }

    public interface Listener {
        void onResult(TestResult result, int completed, int total);

        Listener NONE = (r, c, t) -> { };
    }

    public TestRunner(List<ChatModel> targets, Judge judge, Options options) {
        if (targets.isEmpty()) {
            throw new IllegalArgumentException("At least one target is required");
        }
        this.targets = List.copyOf(targets);
        this.judge = judge;
        this.options = options;
    }

    public static int plannedCalls(TestSuite suite, int targets, int repeats) {
        return suite.cases().size() * targets * repeats;
    }

    public List<TestResult> run(TestSuite suite, AtomicBoolean cancelled, Listener listener) {
        Evaluator evaluator = new Evaluator(suite.task(), judge);
        int total = plannedCalls(suite, targets.size(), options.repeats());
        List<TestResult> results = Collections.synchronizedList(new ArrayList<>(total));
        AtomicInteger completed = new AtomicInteger();
        Semaphore permits = new Semaphore(options.concurrency());

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (ChatModel target : targets) {
                for (TestCase test : suite.cases()) {
                    for (int r = 0; r < options.repeats(); r++) {
                        final int repeat = r;
                        pool.submit(() -> {
                            try {
                                permits.acquire();
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                return;
                            }
                            try {
                                TestResult result = cancelled.get()
                                        ? skipped(target, test, repeat, RunIssue.CANCELLED, "Not sent: the run was cancelled.")
                                        : execute(suite, evaluator, target, test, repeat);
                                results.add(result);
                                listener.onResult(result, completed.incrementAndGet(), total);
                            } finally {
                                permits.release();
                            }
                        });
                    }
                }
            }
        }
        List<TestResult> ordered = new ArrayList<>(results);
        ordered.sort((a, b) -> {
            int c = Integer.compare(targetIndex(a.model()), targetIndex(b.model()));
            if (c != 0) {
                return c;
            }
            c = Integer.compare(caseIndex(suite, a.testId()), caseIndex(suite, b.testId()));
            return c != 0 ? c : Integer.compare(a.repeat(), b.repeat());
        });
        return ordered;
    }

    private TestResult execute(TestSuite suite, Evaluator evaluator, ChatModel target, TestCase test, int repeat) {
        ChatResponse response;
        try {
            response = target.complete(new ChatRequest(suite.task().systemPrompt(), test.input(), options.maxOutputTokens()));
        } catch (Budget.BudgetExceededException e) {
            RunIssue issue = e.limit() == Budget.Limit.COST ? RunIssue.COST_LIMIT : RunIssue.REQUEST_LIMIT;
            return skipped(target, test, repeat, issue, "Not sent: " + e.getMessage() + ".");
        } catch (ResilientChatModel.EndpointUnavailableException e) {
            return skipped(target, test, repeat, RunIssue.ENDPOINT_UNAVAILABLE, e.getMessage());
        } catch (LlmException.ConnectionFailedException e) {
            return failedCall(target, test, repeat, RunIssue.CONNECTION_FAILED, e.getMessage());
        } catch (LlmException e) {
            return failedCall(target, test, repeat, e.status() == 408 ? RunIssue.TIMEOUT : RunIssue.PROVIDER_ERROR, e.getMessage());
        } catch (RuntimeException e) {
            return failedCall(target, test, repeat, RunIssue.PROVIDER_ERROR, "Unexpected error: " + LlmException.describe(e));
        }
        Evaluation evaluation = evaluator.evaluate(test, response);
        Double cost = target.pricing().map(p -> p.cost(response.inputTokens(), response.outputTokens())).orElse(null);
        boolean inconclusive = evaluation.verdict() == Evaluation.Verdict.ERROR;
        return new TestResult(resultId(target, test, repeat), test.id(), target.id(), repeat, test.category(),
                test.input(), test.expectedBehavior(), test.expectedOutput(), test.expectationSource(),
                response.text(), evaluation, response.latencyMs(), response.inputTokens(), response.outputTokens(),
                cost, response.stopReason(), inconclusive ? evaluation.summary() : null,
                inconclusive ? RunIssue.EVALUATION_ERROR : null);
    }

    private static TestResult failedCall(ChatModel target, TestCase test, int repeat, RunIssue issue, String message) {
        return new TestResult(resultId(target, test, repeat), test.id(), target.id(), repeat, test.category(),
                test.input(), test.expectedBehavior(), test.expectedOutput(), test.expectationSource(),
                null, Evaluation.error("Call failed: " + message), 0, 0, 0, null, null, message, issue);
    }

    private static TestResult skipped(ChatModel target, TestCase test, int repeat, RunIssue issue, String message) {
        return new TestResult(resultId(target, test, repeat), test.id(), target.id(), repeat, test.category(),
                test.input(), test.expectedBehavior(), test.expectedOutput(), test.expectationSource(),
                null, Evaluation.skipped(message), 0, 0, 0, null, null, message, issue);
    }

    private static String resultId(ChatModel target, TestCase test, int repeat) {
        return target.id() + "/" + test.id() + "/" + repeat;
    }

    private int targetIndex(String id) {
        for (int i = 0; i < targets.size(); i++) {
            if (targets.get(i).id().equals(id)) {
                return i;
            }
        }
        return Integer.MAX_VALUE;
    }

    private static int caseIndex(TestSuite suite, String testId) {
        for (int i = 0; i < suite.cases().size(); i++) {
            if (suite.cases().get(i).id().equals(testId)) {
                return i;
            }
        }
        return Integer.MAX_VALUE;
    }
}
