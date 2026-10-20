package io.promptfuzzer.llm;

/**
 * How to reach one target, generator or judge. API keys are never part of the config: it names the
 * environment variable that holds the key, so configs can be saved and shared safely.
 *
 * @param provider          which protocol to speak
 * @param model             model id (ignored for endpoints)
 * @param baseUrl           override for the API base URL; required for OpenAI-compatible servers and endpoints
 * @param apiKeyEnv         environment variable holding the API key, or the full Authorization header value for
 *                          endpoints; defaults per provider
 * @param effort            Anthropic effort level ({@code low} … {@code max}); null uses the model default
 * @param inputPricePerMTok override or supply pricing for cost estimates
 * @param outputPricePerMTok override or supply pricing for cost estimates
 * @param responsePointer   JSON pointer to the reply text in an endpoint's response body
 * @param name              display name for endpoints
 */
public record ModelConfig(
        Provider provider,
        String model,
        String baseUrl,
        String apiKeyEnv,
        String effort,
        Double inputPricePerMTok,
        Double outputPricePerMTok,
        String responsePointer,
        String name) {

    public ModelConfig {
        provider = provider == null ? Provider.ANTHROPIC : provider;
    }

    public static ModelConfig anthropic(String model) {
        return new ModelConfig(Provider.ANTHROPIC, model, null, null, null, null, null, null, null);
    }

    public String displayId() {
        return provider == Provider.ENDPOINT ? "endpoint:" + (name == null ? "app" : name) : model;
    }

    public enum Provider {
        ANTHROPIC("ANTHROPIC_API_KEY"),
        OPENAI_COMPATIBLE("OPENAI_API_KEY"),
        ENDPOINT("PF_ENDPOINT_AUTH");

        private final String defaultKeyEnv;

        Provider(String defaultKeyEnv) {
            this.defaultKeyEnv = defaultKeyEnv;
        }

        public String defaultKeyEnv() {
            return defaultKeyEnv;
        }
    }
}
