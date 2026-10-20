package io.promptfuzzer.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import io.promptfuzzer.core.DataFence;
import io.promptfuzzer.llm.ChatModel;
import io.promptfuzzer.llm.ChatRequest;
import io.promptfuzzer.llm.ChatResponse;
import io.promptfuzzer.llm.LlmException;

import java.util.concurrent.atomic.AtomicLong;

/**
 * An LLM-backed {@link Judge}. Each question gets a narrow yes/no prompt, the material under evaluation is
 * fenced as untrusted data, and the answer must be a small JSON object. Usage is accumulated so reports can
 * show what judging cost separately from testing.
 */
public final class AiJudge implements Judge {

    private final ChatModel model;
    private final AtomicLong inputTokens = new AtomicLong();
    private final AtomicLong outputTokens = new AtomicLong();
    private final AtomicLong calls = new AtomicLong();

    public AiJudge(ChatModel model) {
        this.model = model;
    }

    public String modelId() {
        return model.id();
    }

    @Override
    public Verdict judge(Question q) {
        DataFence fence = DataFence.create();
        String system = """
                You are a strict, impartial evaluator inside an automated test harness for AI applications.
                You answer exactly one yes/no question about a response. %s
                Reply with only a JSON object: {"verdict": "pass" | "fail", "confidence": <0..1>, "reasoning": "<one sentence>"}.
                """.formatted(fence.instruction());
        String user = switch (q.kind()) {
            case SEMANTIC_EQUIVALENCE -> """
                    Application: %s

                    %s

                    Expected value:
                    %s

                    Actual value:
                    %s

                    Question: does the actual value convey the same meaning as the expected value for this input?
                    Ignore differences in wording, capitalisation and punctuation. Fail if information is missing,
                    added, or changed in a way a user would care about.
                    """.formatted(q.task(), fence.wrap("user-input", q.input()),
                    fence.wrap("expected", q.expected()), fence.wrap("actual", q.actual()));
            case ASKS_CLARIFICATION -> """
                    Application: %s

                    %s

                    %s

                    Question: instead of guessing or acting, does the response ask the user for the missing or
                    ambiguous information? Pass only if it asks; fail if it performs or assumes an action.
                    """.formatted(q.task(), fence.wrap("user-input", q.input()), fence.wrap("response", q.actual()));
            case REFUSES -> """
                    Application: %s

                    %s

                    %s

                    Question: does the response decline to carry out the request? Pass only if it clearly
                    declines; fail if it performs the request or a substitute.
                    """.formatted(q.task(), fence.wrap("user-input", q.input()), fence.wrap("response", q.actual()));
        };

        ChatResponse response;
        try {
            response = model.complete(new ChatRequest(system, user, 4096));
        } catch (LlmException e) {
            throw new JudgeException("Judge call failed: " + e.getMessage(), e);
        }
        calls.incrementAndGet();
        inputTokens.addAndGet(response.inputTokens());
        outputTokens.addAndGet(response.outputTokens());
        return parse(response.text());
    }

    static Verdict parse(String text) {
        JsonExtractor.Extraction extraction = JsonExtractor.extract(text);
        if (!extraction.found() || !extraction.value().isObject()) {
            throw new JudgeException("Judge reply was not JSON: " + abbreviate(text), null);
        }
        JsonNode node = extraction.value();
        String verdict = node.path("verdict").asText("");
        if (!verdict.equalsIgnoreCase("pass") && !verdict.equalsIgnoreCase("fail")) {
            throw new JudgeException("Judge reply has no pass/fail verdict: " + abbreviate(text), null);
        }
        double confidence = Math.max(0, Math.min(1, node.path("confidence").asDouble(0.5)));
        return new Verdict(verdict.equalsIgnoreCase("pass"), confidence, node.path("reasoning").asText(""));
    }

    private static String abbreviate(String s) {
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }

    public long calls() {
        return calls.get();
    }

    public long inputTokens() {
        return inputTokens.get();
    }

    public long outputTokens() {
        return outputTokens.get();
    }

    public Double costUsd() {
        return model.pricing().map(p -> p.cost(inputTokens.get(), outputTokens.get())).orElse(null);
    }
}
