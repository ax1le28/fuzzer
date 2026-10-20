package io.promptfuzzer.core;

/**
 * The kinds of input perturbation PromptFuzzer generates. Each category carries a short description that is
 * shown in the dashboard and handed to the test generator so it knows what to produce.
 */
public enum TestCategory {
    BASELINE("Baseline", "The original seed example, unchanged. Establishes that the happy path works."),
    PARAPHRASE("Paraphrase", "Same meaning, different wording and vocabulary."),
    TYPOS("Typos & grammar", "Spelling mistakes, swapped letters, missing punctuation and broken grammar."),
    SLANG("Slang & informal", "Casual, abbreviated or slang-heavy phrasing, as people actually type."),
    UNUSUAL_STRUCTURE("Unusual structure", "Inverted word order, run-on sentences, fragments, or nested clauses."),
    IRRELEVANT_CONTEXT("Irrelevant context", "The real request buried among unrelated chatter or details."),
    NEGATION("Negation", "Negated or exception-based phrasing that changes or constrains the meaning."),
    CONTRADICTION("Contradiction", "Self-contradicting or mid-sentence corrected instructions."),
    AMBIGUITY("Ambiguity", "Requests that lack information needed to answer; the right move is to ask."),
    EDGE_CASE("Edge case", "Boundary values, unsupported devices or values, empty-ish or extreme input."),
    INJECTION("Prompt injection", "Input that tries to override the application's instructions or output format.");

    private final String displayName;
    private final String description;

    TestCategory(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String displayName() {
        return displayName;
    }

    public String description() {
        return description;
    }
}
