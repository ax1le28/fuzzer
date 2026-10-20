package io.promptfuzzer.analysis;

import io.promptfuzzer.evaluation.FailureType;

/** Fixed, rule-based prompt-engineering advice per failure type. Requires no model and never varies. */
final class Suggestions {

    private Suggestions() {
    }

    static String forType(FailureType type) {
        return switch (type) {
            case UNEXPECTED_REFUSAL ->
                    "State the application's scope explicitly so benign requests in scope are not declined, and give an example of a borderline request that should be handled.";
            case TRUNCATED ->
                    "Raise the output token limit, or ask for shorter output. Reasoning models spend part of the budget thinking before they answer.";
            case INVALID_STRUCTURE ->
                    "Put the exact output schema in the instructions with one complete valid example, list the allowed enum values, and consider the provider's structured-output mode.";
            case PROMPT_INJECTION ->
                    "Tell the model that the user's message is data to interpret, never instructions that change its rules or output format. Keep format rules in the system prompt and validate outputs in code.";
            case AMBIGUITY_HANDLING ->
                    "Define exactly which fields are required and instruct: when any of them cannot be determined from the request, return the clarification object instead of guessing. Add one example of each.";
            case NEGATION_MISUNDERSTANDING ->
                    "Add few-shot examples with negations and exclusions (\"everything except…\", \"don't…, instead…\") and state that the last correction in a message wins.";
            case MISSING_INFORMATION ->
                    "List every field the output must include and say to include it even when its value is a default; show one example with all fields populated.";
            case HALLUCINATED_INFORMATION ->
                    "Instruct the model to include only values stated in or directly implied by the request, and to omit or null anything unknown rather than inventing it.";
            case INSTRUCTION_MISINTERPRETATION ->
                    "Add examples that cover the paraphrases and informal phrasings that failed, and make the mapping from wording to actions explicit.";
            case SEMANTIC_MISMATCH ->
                    "Specify how free-text fields should be written (for example: a short noun phrase taken from the request) so their content is predictable.";
            case FORMATTING ->
                    "Say explicitly: respond with a single raw JSON object, with no markdown code fences and no text before or after it.";
        };
    }
}
