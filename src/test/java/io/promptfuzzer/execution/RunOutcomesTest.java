package io.promptfuzzer.execution;

import io.promptfuzzer.TestFixtures;
import io.promptfuzzer.analysis.FailureAnalysis;
import io.promptfuzzer.analysis.ModelSummary;
import io.promptfuzzer.analysis.ReportBuilder;
import io.promptfuzzer.analysis.RunReport;
import io.promptfuzzer.app.ModelProvider;
import io.promptfuzzer.core.Json;
import io.promptfuzzer.core.TestCase;
import io.promptfuzzer.core.TestCategory;
import io.promptfuzzer.core.TestSuite;
import io.promptfuzzer.evaluation.Evaluation;
import io.promptfuzzer.evaluation.FailureType;
import io.promptfuzzer.llm.Budget;
import io.promptfuzzer.llm.ChatModel;
import io.promptfuzzer.llm.ChatRequest;
import io.promptfuzzer.llm.ChatResponse;
import io.promptfuzzer.llm.LlmException;
import io.promptfuzzer.llm.ModelConfig;
import io.promptfuzzer.llm.ModelFactory;
import io.promptfuzzer.llm.Pricing;
import io.promptfuzzer.llm.ProviderPreset;
import io.promptfuzzer.llm.ResilientChatModel;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs that produce errors, skipped calls or both: they must never read as a model passing (or failing), and
 * every limit must be reported as the limit it is. No real model or network service is used.
 */
class RunOutcomesTest {

    private static final String GOOD = "{\"action\": \"turn_off\", \"device\": \"lights\", \"room\": \"kitchen\"}";
    private static final ResilientChatModel.RetryPolicy FAST_RETRY =
            new ResilientChatModel.RetryPolicy(4, Duration.ofMillis(1), Duration.ofMillis(2));

    private static TestSuite suite(int n) {
        List<TestCase> cases = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            cases.add(TestFixtures.match(TestCategory.PARAPHRASE, "turn off the kitchen lights #" + i, GOOD));
        }
        return new TestSuite("suite-test", "test", TestFixtures.smartHome(), cases, null, null);
    }

    /** A model whose every call is answered by {@code behaviour}, counting calls. */
    private static ChatModel stub(String id, AtomicInteger calls, Pricing pricing, Supplier<ChatResponse> behaviour) {
        return new ChatModel() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public Optional<Pricing> pricing() {
                return Optional.ofNullable(pricing);
            }

            @Override
            public ChatResponse complete(ChatRequest request) {
                calls.incrementAndGet();
                return behaviour.get();
            }
        };
    }

    private static List<TestResult> run(ChatModel target, TestSuite suite, int concurrency) {
        return new TestRunner(List.of(target), null, new TestRunner.Options(1, concurrency, 256))
                .run(suite, new AtomicBoolean(), TestRunner.Listener.NONE);
    }

    private static RunReport.RunSettings settings(String model, Budget budget) {
        return new RunReport.RunSettings(List.of(model), 1, 1, 256, null, budget.maxCostUsd(), budget.maxRequests());
    }

    /** 1. Every call fails to connect: nothing is graded, so the pass rate is "not measured", not 0%, and nothing passed. */
    @Test
    void allConnectionErrorsMeasureNoPassRate() {
        AtomicInteger calls = new AtomicInteger();
        ChatModel down = stub("down", calls, null, () -> {
            throw LlmException.connectionFailed("http://127.0.0.1:9/v1/chat/completions", new java.net.ConnectException());
        });
        Budget budget = new Budget(100, 1.0);
        List<TestResult> results = run(new ResilientChatModel(down, null, budget, FAST_RETRY), suite(6), 1);

        ModelSummary m = ReportBuilder.summarize(List.of("down"), results).get(0);
        assertEquals(0, m.passed());
        assertEquals(0, m.failed());
        assertEquals(6, m.errors() + m.skipped());
        assertNull(m.passRate(), "nothing graded: pass rate is N/A, not 0%");
        assertNull(m.passRateLow());
        assertNull(m.passRateHigh());
        assertNull(m.byCategory().get(TestCategory.PARAPHRASE).passRate(), "a category with only errors has no pass rate either");
        assertFalse(Json.write(m).contains("\"passRate\""),
                "no pass rate is sent (nulls are omitted), so the dashboard shows N/A instead of a number");

        FailureAnalysis analysis = ReportBuilder.analyze(results);
        assertEquals(FailureAnalysis.Outcome.NOTHING_GRADED, analysis.outcome());
        assertNotEquals(FailureAnalysis.Outcome.ALL_PASSED, analysis.outcome());
        assertTrue(analysis.clusters().isEmpty());
        assertFalse(analysis.issues().isEmpty(), "the errors are reported as run problems instead");
        assertEquals(RunReport.Status.COMPLETED_WITH_ERRORS, ReportBuilder.completionStatus(results, false));

        String message = ReportBuilder.issueSummary(results, settings("down", budget), budget.spentUsd());
        assertTrue(message.startsWith("No response could be graded"), message);
        assertFalse(message.toLowerCase().contains("passed"), message);
        assertTrue(message.contains("ConnectException"), message);
        assertFalse(message.contains("null"), message);
    }

    /** 2. The request ceiling stops calls: they are skipped, attributed to the request limit and never to money. */
    @Test
    void requestCapSkipsAreReportedAsTheRequestLimit() {
        AtomicInteger calls = new AtomicInteger();
        Budget budget = new Budget(1, 5.0);
        budget.acquire(); // the only request has already been used
        ChatModel target = new ResilientChatModel(stub("m", calls, new Pricing(1, 1), () -> TestFixtures.reply(GOOD)),
                null, budget, FAST_RETRY);
        List<TestResult> results = run(target, suite(4), 2);

        assertEquals(0, calls.get(), "nothing is sent once the ceiling is reached");
        results.forEach(r -> {
            assertEquals(Evaluation.Verdict.SKIPPED, r.verdict());
            assertEquals(RunIssue.REQUEST_LIMIT, r.issue());
            assertFalse(r.evaluation().summary().startsWith("Call failed"), "a skip is not dressed up as a model error");
        });
        ModelSummary m = ReportBuilder.summarize(List.of("m"), results).get(0);
        assertEquals(0, m.errors());
        assertEquals(4, m.skipped());
        assertEquals(Map.of(RunIssue.REQUEST_LIMIT, 4), m.issues());

        String message = ReportBuilder.issueSummary(results, settings("m", budget), budget.spentUsd());
        assertTrue(message.contains("request limit of 1 requests"), message);
        assertFalse(message.contains("$"), "a request limit is not a dollar limit: " + message);
        assertFalse(message.toLowerCase().contains("cost"), message);
    }

    /** 3. Errors and skipped calls are counted separately and excluded from the pass-rate denominator. */
    @Test
    void mixedResultsKeepErrorsAndSkipsOutOfThePassRate() {
        List<TestResult> results = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            results.add(result(i, TestCategory.PARAPHRASE, verdictOf(Evaluation.Verdict.PASS), GOOD, null));
        }
        results.add(result(3, TestCategory.PARAPHRASE, verdictOf(Evaluation.Verdict.FAIL), "{}", null));
        results.add(result(4, TestCategory.NEGATION, Evaluation.error("Call failed: HTTP 500"), null, RunIssue.PROVIDER_ERROR));
        results.add(result(5, TestCategory.NEGATION, Evaluation.error("Call failed: refused"), null, RunIssue.CONNECTION_FAILED));
        results.add(result(6, TestCategory.NEGATION, Evaluation.skipped("Not sent"), null, RunIssue.REQUEST_LIMIT));
        results.add(result(7, TestCategory.NEGATION, Evaluation.skipped("Not sent"), null, RunIssue.COST_LIMIT));

        ModelSummary m = ReportBuilder.summarize(List.of("m"), results).get(0);
        assertEquals(8, m.total());
        assertEquals(3, m.passed());
        assertEquals(1, m.failed());
        assertEquals(2, m.errors());
        assertEquals(2, m.skipped());
        assertEquals(m.total(), m.passed() + m.failed() + m.errors() + m.skipped());
        assertEquals(0.75, m.passRate(), 1e-9, "3 of 4 graded; errors and skips are not in the denominator");
        assertEquals(0.3006, m.passRateLow(), 1e-3, "Wilson interval on n = 4, not n = 8");
        assertEquals(0.9544, m.passRateHigh(), 1e-3);

        ModelSummary.CategoryStats negation = m.byCategory().get(TestCategory.NEGATION);
        assertEquals(2, negation.errors());
        assertEquals(2, negation.skipped());
        assertNull(negation.passRate(), "a category with only errors and skips has no measured pass rate");
        assertEquals(0.75, m.byCategory().get(TestCategory.PARAPHRASE).passRate(), 1e-9);

        FailureAnalysis analysis = ReportBuilder.analyze(results);
        assertEquals(FailureAnalysis.Outcome.FAILURES_FOUND, analysis.outcome());
        assertEquals(1, analysis.clusters().stream().mapToInt(FailureAnalysis.Cluster::count).sum());
        assertEquals(4, analysis.issues().stream().mapToInt(FailureAnalysis.IssueGroup::count).sum());
        assertEquals(RunReport.Status.COMPLETED_WITH_ERRORS, ReportBuilder.completionStatus(results, false));

        List<TestResult> passesOnly = results.subList(0, 3);
        assertEquals(FailureAnalysis.Outcome.ALL_PASSED, ReportBuilder.analyze(passesOnly).outcome());
        assertEquals(RunReport.Status.COMPLETED, ReportBuilder.completionStatus(passesOnly, false));
        List<TestResult> passesAndSkips = List.of(results.get(0), results.get(6));
        assertEquals(FailureAnalysis.Outcome.PASSED_WITH_ISSUES, ReportBuilder.analyze(passesAndSkips).outcome(),
                "skipped calls keep a run from being reported as all passed");
    }

    /** 4. An unreachable local server ends the run in seconds, with a clear reason, instead of grinding through retries. */
    @Test
    void unreachableLocalEndpointFailsFast() throws IOException {
        String base = "http://127.0.0.1:" + closedPort() + "/v1";
        ModelConfig config = ProviderPreset.OLLAMA.config("qwen2.5:7b", base);
        ModelProvider provider = new ModelProvider(ModelFactory.fromMap(Map.of()), 6000);

        IllegalArgumentException preflight = assertThrows(IllegalArgumentException.class, () -> provider.preflight(config));
        assertTrue(preflight.getMessage().contains("is not running"), preflight.getMessage());
        assertTrue(preflight.getMessage().contains("ConnectException"), preflight.getMessage());

        // Even without the preflight (e.g. the server stops mid-run), the run stops calling the endpoint.
        int tests = 30;
        int concurrency = 2;
        Budget budget = new Budget(Budget.requestCeiling(tests, 4, 600), 1.0);
        long start = System.nanoTime();
        List<TestResult> results = new TestRunner(List.of(provider.create(config, budget)), null,
                new TestRunner.Options(1, concurrency, 256)).run(suite(tests), new AtomicBoolean(), TestRunner.Listener.NONE);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(elapsedMs < 5_000, "took " + elapsedMs + " ms");
        long errors = results.stream().filter(r -> r.issue() == RunIssue.CONNECTION_FAILED).count();
        long skipped = results.stream().filter(r -> r.issue() == RunIssue.ENDPOINT_UNAVAILABLE).count();
        assertEquals(tests, errors + skipped);
        assertTrue(errors <= ResilientChatModel.CONNECTION_FAILURE_LIMIT + concurrency, "attempted " + errors + " connections");
        assertTrue(budget.requestsUsed() <= ResilientChatModel.CONNECTION_FAILURE_LIMIT + concurrency,
                "connection failures are not retried: " + budget.requestsUsed() + " requests");

        String error = results.stream().filter(r -> r.issue() == RunIssue.CONNECTION_FAILED).findFirst().orElseThrow().error();
        assertTrue(error.contains("Cannot connect to http://127.0.0.1:"), error);
        assertTrue(error.contains("ConnectException"), error);
        assertTrue(error.contains("running"), "says how to recover: " + error);
        assertFalse(error.contains("null"), error);

        String message = ReportBuilder.issueSummary(results, settings("qwen2.5:7b", budget), budget.spentUsd());
        assertTrue(message.contains("stopped calling"), message);
        assertEquals(FailureAnalysis.Outcome.NOTHING_GRADED, ReportBuilder.analyze(results).outcome());
    }

    /** 5. The dollar budget still caps spending, however generous the request ceiling is. */
    @Test
    void costLimitStillCapsSpending() {
        AtomicInteger calls = new AtomicInteger();
        // $2 per call: 1M tokens in at $1/M plus 1M out at $1/M.
        ChatModel priced = stub("priced", calls, new Pricing(1, 1), () -> new ChatResponse(GOOD, 1_000_000, 1_000_000, 5, "end_turn"));
        Budget budget = new Budget(Budget.requestCeiling(10, 4, 600), 3.5);
        List<TestResult> results = run(new ResilientChatModel(priced, null, budget, FAST_RETRY), suite(10), 1);

        assertEquals(2, calls.get(), "$2 + $2 crosses $3.50; nothing after that is sent");
        assertEquals(40, budget.maxRequests(), "the request ceiling is far from binding");
        long costSkips = results.stream().filter(r -> r.issue() == RunIssue.COST_LIMIT).count();
        assertEquals(8, costSkips);
        assertTrue(results.stream().noneMatch(r -> r.issue() == RunIssue.REQUEST_LIMIT));

        String message = ReportBuilder.issueSummary(results, settings("priced", budget), budget.spentUsd());
        assertTrue(message.contains("cost limit of $3.50"), message);
        assertTrue(message.contains("spent $4.0000"), message);
    }

    /** Retries spend requests; the ceiling is sized for them, so transient errors do not cut a run short. */
    @Test
    void requestCeilingLeavesRoomForRetries() {
        AtomicInteger calls = new AtomicInteger();
        ChatModel flaky = stub("flaky", calls, null, () -> {
            if (calls.get() % 2 == 1) {
                throw LlmException.forStatus(429, "slow down", Duration.ZERO);
            }
            return TestFixtures.reply(GOOD);
        });
        int tests = 8;
        Budget budget = new Budget(Budget.requestCeiling(tests, FAST_RETRY.maxAttempts(), 600), 1.0);
        List<TestResult> results = run(new ResilientChatModel(flaky, null, budget, FAST_RETRY), suite(tests), 1);

        assertTrue(results.stream().allMatch(r -> r.verdict().graded()), "every test was graded despite retries");
        assertEquals(2 * tests, budget.requestsUsed(), "each retry is counted as a request");
        assertEquals(Math.min(600, tests * 4), Budget.requestCeiling(tests, 4, 600));
        assertEquals(600, Budget.requestCeiling(1_000, 4, 600), "PF_MAX_REQUESTS remains the hard limit");
    }

    /** Reports saved by the earlier version (skips stored as errors, 0% pass rates, "completed") are reclassified on load. */
    @Test
    void legacyReportsAreReclassifiedOnLoad() {
        List<TestResult> old = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            old.add(result(i, TestCategory.PARAPHRASE,
                    Evaluation.error("Call failed: Network error calling http://localhost:11434/v1/chat/completions: null"), null, null));
        }
        for (int i = 3; i < 5; i++) {
            old.add(result(i, TestCategory.PARAPHRASE, Evaluation.error("Call failed: Skipped: Request limit of 44 reached"), null, null));
        }
        old = old.stream().map(r -> new TestResult(r.id(), r.testId(), r.model(), r.repeat(), r.category(), r.input(),
                r.expectedBehavior(), r.expectedOutput(), r.expectationSource(), null, r.evaluation(), 0, 0, 0, null, null,
                r.evaluation().summary().replace("Call failed: ", ""), null)).toList();
        RunReport.RunSettings settings = new RunReport.RunSettings(List.of("m"), 1, 4, 4096, null, 1.0, 44);
        FailureAnalysis legacyAnalysis = new FailureAnalysis(null, List.of(), List.of(), null);
        RunReport legacy = new RunReport("run-old", "s", "suite", RunReport.Status.COMPLETED, null, null, settings, 5, 5,
                ReportBuilder.summarize(List.of("m"), old), old, legacyAnalysis, null, null,
                "2 call(s) skipped because the budget was reached ($0,0000 of $1.0).", false);

        RunReport upgraded = ReportBuilder.upgradeLegacy(legacy);
        assertEquals(RunReport.Status.COMPLETED_WITH_ERRORS, upgraded.status());
        assertEquals(FailureAnalysis.Outcome.NOTHING_GRADED, upgraded.analysis().outcome());
        ModelSummary m = upgraded.models().get(0);
        assertEquals(3, m.errors());
        assertEquals(2, m.skipped());
        assertNull(m.passRate());
        assertEquals(Map.of(RunIssue.CONNECTION_FAILED, 3, RunIssue.REQUEST_LIMIT, 2), m.issues());
        assertTrue(upgraded.message().contains("request limit of 44"), upgraded.message());
        assertFalse(upgraded.message().contains("$"), upgraded.message());
        assertEquals(upgraded, ReportBuilder.upgradeLegacy(upgraded), "upgrading is idempotent");
    }

    private static Evaluation verdictOf(Evaluation.Verdict verdict) {
        return new Evaluation(verdict, Evaluation.Method.DETERMINISTIC,
                verdict == Evaluation.Verdict.FAIL ? FailureType.MISSING_INFORMATION : null, "graded", List.of(), List.of(), null);
    }

    private static TestResult result(int i, TestCategory category, Evaluation evaluation, String raw, RunIssue issue) {
        TestCase test = TestFixtures.match(category, "input " + i, GOOD);
        return new TestResult("m/" + test.id() + "/0", test.id(), "m", 0, category, test.input(), test.expectedBehavior(),
                test.expectedOutput(), test.expectationSource(), raw, evaluation, raw == null ? 0 : 100, 0, 0, null, null,
                issue == null ? null : evaluation.summary(), issue);
    }

    /** A loopback port with nothing listening on it. */
    static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }
}
