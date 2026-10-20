package io.promptfuzzer.llm;

import java.net.URI;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;

/** Builds {@link ChatModel}s from configs, resolving API keys from the environment. */
public final class ModelFactory {

    /**
     * Only variables that look like credentials may be referenced. This stops a config (which may arrive
     * through the dashboard) from naming an arbitrary variable and sending its value to an arbitrary URL.
     */
    private static final Pattern KEY_ENV = Pattern.compile("[A-Z][A-Z0-9_]*(API_KEY|TOKEN|AUTH)");

    private final Function<String, String> env;

    public ModelFactory(Function<String, String> env) {
        this.env = env;
    }

    public static ModelFactory fromSystemEnv() {
        return new ModelFactory(System::getenv);
    }

    public static ModelFactory fromMap(Map<String, String> vars) {
        return new ModelFactory(vars::get);
    }

    /** True when the provider's default key variable is set (keys themselves are never exposed). */
    public boolean hasKey(ModelConfig.Provider provider) {
        String v = env.apply(provider.defaultKeyEnv());
        return v != null && !v.isBlank();
    }

    public ChatModel create(ModelConfig config) {
        String keyEnv = Optional.ofNullable(config.apiKeyEnv()).filter(s -> !s.isBlank())
                .orElse(config.provider().defaultKeyEnv());
        if (!KEY_ENV.matcher(keyEnv).matches()) {
            throw new IllegalArgumentException("API key variable '" + keyEnv
                    + "' is not allowed; use a name ending in API_KEY, TOKEN or AUTH");
        }
        String key = env.apply(keyEnv);
        Pricing pricing = config.inputPricePerMTok() != null && config.outputPricePerMTok() != null
                ? new Pricing(config.inputPricePerMTok(), config.outputPricePerMTok())
                : null;

        return switch (config.provider()) {
            case ANTHROPIC -> {
                requireModel(config);
                if (key == null || key.isBlank()) {
                    throw new IllegalArgumentException(keyEnv + " is not set. Export your Anthropic API key and restart.");
                }
                yield new AnthropicChatModel(key, config.baseUrl(), config.model(), config.effort(), pricing);
            }
            case OPENAI_COMPATIBLE -> {
                requireModel(config);
                String base = requireUrl(config.baseUrl(), "baseUrl");
                yield new OpenAiCompatibleChatModel(base, key, config.model(), pricing);
            }
            case ENDPOINT -> new HttpEndpointModel(
                    config.name() == null || config.name().isBlank() ? "app" : config.name(),
                    requireUrl(config.baseUrl(), "Endpoint URL"), key, config.responsePointer());
        };
    }

    private static void requireModel(ModelConfig config) {
        if (config.model() == null || config.model().isBlank()) {
            throw new IllegalArgumentException("A model id is required for " + config.provider());
        }
    }

    private static String requireUrl(String url, String what) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException(what + " is required");
        }
        URI uri = URI.create(url.trim());
        if (!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme())) {
            throw new IllegalArgumentException(what + " must be an http(s) URL");
        }
        return url.trim();
    }
}
