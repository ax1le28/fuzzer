package io.promptfuzzer.execution;

import com.fasterxml.jackson.databind.JsonNode;
import io.promptfuzzer.core.ExpectedBehavior;
import io.promptfuzzer.core.TestCase;
import io.promptfuzzer.core.TestCategory;
import io.promptfuzzer.evaluation.Evaluation;

/**
 * The outcome of sending one test input to one target once.
 *
 * @param id          unique within the run
 * @param testId      the test case
 * @param model       target id
 * @param repeat      0-based repetition index
 * @param rawOutput   the response exactly as received (untrusted; always rendered as text)
 * @param error       transport or provider error, when the call failed
 */
public record TestResult(
        String id,
        String testId,
        String model,
        int repeat,
        TestCategory category,
        String input,
        ExpectedBehavior expectedBehavior,
        JsonNode expectedOutput,
        TestCase.ExpectationSource expectationSource,
        String rawOutput,
        Evaluation evaluation,
        long latencyMs,
        long inputTokens,
        long outputTokens,
        Double costUsd,
        String stopReason,
        String error) {

    public Evaluation.Verdict verdict() {
        return evaluation.verdict();
    }
}
