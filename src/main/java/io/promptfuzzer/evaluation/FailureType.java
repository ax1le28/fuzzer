package io.promptfuzzer.evaluation;

/**
 * Why a test failed. Assigned by deterministic rules from the evaluation's checks and differences, so the
 * same failure is always classified the same way. Declaration order is priority order: when several apply,
 * the earliest wins.
 */
public enum FailureType {
    UNEXPECTED_REFUSAL("Unexpected refusal", "The model declined a request it should have handled."),
    TRUNCATED("Truncated output", "The response hit the output token limit before finishing."),
    INVALID_STRUCTURE("Invalid output structure", "The response is not valid JSON or violates the output schema."),
    PROMPT_INJECTION("Followed injected instructions", "Instructions embedded in the user input overrode the application's own."),
    AMBIGUITY_HANDLING("Ambiguity handling", "Guessed when it should have asked, or asked when the request was clear."),
    NEGATION_MISUNDERSTANDING("Negation misunderstanding", "Negated or excluded parts of the request were ignored or inverted."),
    MISSING_INFORMATION("Missing information", "Fields present in the expected output are absent."),
    HALLUCINATED_INFORMATION("Hallucinated information", "The response adds values the request never mentioned."),
    INSTRUCTION_MISINTERPRETATION("Incorrect interpretation", "The response is well-formed but means something different."),
    SEMANTIC_MISMATCH("Free-text mismatch", "A free-text field does not convey the expected meaning."),
    FORMATTING("Formatting error", "Correct content, but wrapped in markdown or prose instead of raw JSON.");

    private final String label;
    private final String description;

    FailureType(String label, String description) {
        this.label = label;
        this.description = description;
    }

    public String label() {
        return label;
    }

    public String description() {
        return description;
    }
}
