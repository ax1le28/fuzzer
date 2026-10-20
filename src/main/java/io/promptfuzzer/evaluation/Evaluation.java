package io.promptfuzzer.evaluation;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * The outcome of evaluating one response.
 *
 * @param verdict      pass or fail (the model was graded), error (the call or the grading failed) or skipped
 *                     (the call was never sent); only pass and fail count towards a pass rate
 * @param verification the weakest kind of evidence the verdict rests on: a pass that needed an AI judge is
 *                     {@code AI_JUDGE}, never {@code DETERMINISTIC}
 * @param failureType  primary failure classification, null unless failed
 * @param summary      one-line explanation
 * @param checks       every check that ran, in order
 * @param differences  field-level differences against the expected output
 * @param parsed       the JSON value found in the response, if any
 */
public record Evaluation(
        Verdict verdict,
        Method verification,
        FailureType failureType,
        String summary,
        List<Check> checks,
        List<JsonDiff.Difference> differences,
        JsonNode parsed) {

    public Evaluation {
        checks = checks == null ? List.of() : List.copyOf(checks);
        differences = differences == null ? List.of() : List.copyOf(differences);
    }

    public static Evaluation error(String summary) {
        return new Evaluation(Verdict.ERROR, null, null, summary, List.of(), List.of(), null);
    }

    public static Evaluation skipped(String summary) {
        return new Evaluation(Verdict.SKIPPED, null, null, summary, List.of(), List.of(), null);
    }

    public enum Verdict {
        PASS, FAIL, ERROR, SKIPPED;

        /** True when the model's behaviour was actually graded. */
        public boolean graded() {
            return this == PASS || this == FAIL;
        }
    }

    /** How a check reached its conclusion. Ordered from strongest to weakest evidence. */
    public enum Method {
        /** Objectively verified by code: parsing, schema validation, exact comparison. */
        DETERMINISTIC,
        /** The opinion of an AI judge. Useful, but not ground truth. */
        AI_JUDGE,
        /** A rule of thumb (e.g. word overlap) used when no judge is configured. */
        HEURISTIC
    }

    /**
     * @param name        what was checked
     * @param method      how
     * @param passed      result
     * @param detail      explanation
     * @param confidence  judge's self-reported confidence (AI_JUDGE only)
     * @param failureType classification if this check failed
     */
    public record Check(String name, Method method, boolean passed, String detail, Double confidence,
                        FailureType failureType) {

        public static Check verified(String name, boolean passed, String detail, FailureType onFailure) {
            return new Check(name, Method.DETERMINISTIC, passed, detail, null, passed ? null : onFailure);
        }
    }
}
