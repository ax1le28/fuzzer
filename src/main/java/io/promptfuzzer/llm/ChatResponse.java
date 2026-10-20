package io.promptfuzzer.llm;

/**
 * A model reply with usage accounting.
 *
 * @param text         concatenated text content
 * @param inputTokens  billed input tokens (0 when the provider does not report usage)
 * @param outputTokens billed output tokens
 * @param latencyMs    wall-clock time of the successful attempt
 * @param stopReason   provider stop reason, normalized to lower case (e.g. {@code end_turn}, {@code refusal})
 */
public record ChatResponse(String text, long inputTokens, long outputTokens, long latencyMs, String stopReason) {

    public static final String REFUSAL = "refusal";
    public static final String MAX_TOKENS = "max_tokens";

    public ChatResponse {
        text = text == null ? "" : text;
    }

    public boolean refused() {
        return REFUSAL.equals(stopReason);
    }

    public boolean truncated() {
        return MAX_TOKENS.equals(stopReason);
    }
}
