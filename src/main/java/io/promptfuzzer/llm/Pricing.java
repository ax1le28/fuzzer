package io.promptfuzzer.llm;

import java.util.Map;
import java.util.Optional;

/**
 * Per-million-token prices in USD. Used only for estimates; the provider's invoice is the source of truth.
 */
public record Pricing(double inputPerMTok, double outputPerMTok) {

    /** Anthropic first-party list prices (standard tier, as of October 2026). */
    private static final Map<String, Pricing> KNOWN = Map.ofEntries(
            Map.entry("claude-fable-5-1", new Pricing(10.00, 50.00)),
            Map.entry("claude-opus-5-5", new Pricing(4.00, 20.00)),
            Map.entry("claude-opus-5", new Pricing(5.00, 25.00)),
            Map.entry("claude-opus-4-8", new Pricing(5.00, 25.00)),
            Map.entry("claude-sonnet-5-5", new Pricing(2.00, 10.00)),
            Map.entry("claude-sonnet-5", new Pricing(2.00, 10.00)),
            Map.entry("claude-sonnet-4-6", new Pricing(3.00, 15.00)),
            Map.entry("claude-haiku-5-5", new Pricing(0.10, 0.50)),
            Map.entry("claude-haiku-4-5", new Pricing(1.00, 5.00)));

    public Pricing {
        if (inputPerMTok < 0 || outputPerMTok < 0) {
            throw new IllegalArgumentException("Prices cannot be negative");
        }
    }

    public static Optional<Pricing> forModel(String modelId) {
        return Optional.ofNullable(KNOWN.get(modelId));
    }

    public static Map<String, Pricing> known() {
        return KNOWN;
    }

    public double cost(long inputTokens, long outputTokens) {
        return (inputTokens * inputPerMTok + outputTokens * outputPerMTok) / 1_000_000.0;
    }
}
