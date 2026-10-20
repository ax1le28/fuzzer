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
 * @param error       why the call failed or was skipped, with what to do about it
 * @param issue       classification of an {@code ERROR} or {@code SKIPPED} result, null for graded ones
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
        String error,
        RunIssue issue) {

    public Evaluation.Verdict verdict() {
        return evaluation.verdict();
    }
}
