package io.promptfuzzer.llm;

/**
 * A single-turn request.
 *
 * @param system    system instructions (trusted, written by PromptFuzzer or the task author)
 * @param user      the user turn (may contain untrusted test data)
 * @param maxTokens output cap
 */
public record ChatRequest(String system, String user, int maxTokens) {

    public ChatRequest {
        if (maxTokens <= 0) {
            throw new IllegalArgumentException("maxTokens must be positive");
        }
        user = user == null ? "" : user;
    }
}
