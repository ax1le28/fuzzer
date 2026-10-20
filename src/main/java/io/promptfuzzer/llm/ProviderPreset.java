package io.promptfuzzer.llm;

import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Ready-made connection settings for common providers, including free and local ones. Every preset other than
 * Anthropic speaks the OpenAI-compatible protocol, so a preset is only a base URL, a key variable and a sensible
 * request rate; the engine itself is provider-agnostic.
 */
public enum ProviderPreset {

    ANTHROPIC("anthropic", "Anthropic", ModelConfig.Provider.ANTHROPIC, null, "ANTHROPIC_API_KEY",
            List.of("claude-opus-5-5", "claude-sonnet-5-5", "claude-haiku-5-5"), 0, false,
            "Paid API. Prices are built in, so cost estimates are exact."),
    GEMINI("gemini", "Google Gemini (free tier)", ModelConfig.Provider.OPENAI_COMPATIBLE,
            "https://generativelanguage.googleapis.com/v1beta/openai", "GEMINI_API_KEY",
            List.of("gemini-2.5-flash", "gemini-2.5-flash-lite"), 10, false,
            "Free key from aistudio.google.com, no card required. The free tier allows only a few requests per minute."),
    GROQ("groq", "Groq (free tier)", ModelConfig.Provider.OPENAI_COMPATIBLE,
            "https://api.groq.com/openai/v1", "GROQ_API_KEY",
            List.of("llama-3.3-70b-versatile", "llama-3.1-8b-instant"), 30, false,
            "Free key from console.groq.com, no card required. Fast open-weight models."),
    OLLAMA("ollama", "Ollama (local, no key)", ModelConfig.Provider.OPENAI_COMPATIBLE,
            "http://localhost:11434/v1", null,
            List.of("qwen2.5:7b", "llama3.1:8b"), 0, true,
            "Runs on your computer for free. Install Ollama and pull a model first, for example: ollama pull qwen2.5:7b"),
    OPENAI("openai", "Other OpenAI-compatible server", ModelConfig.Provider.OPENAI_COMPATIBLE,
            "https://api.openai.com/v1", "OPENAI_API_KEY",
            List.of(), 0, false,
            "Any /chat/completions server: OpenAI, OpenRouter, LM Studio, vLLM, llama.cpp."),
    ENDPOINT("endpoint", "Your app endpoint", ModelConfig.Provider.ENDPOINT, null, "PF_ENDPOINT_AUTH",
            List.of(), 0, false,
            "Your own HTTP service. It receives {\"input\": ...} and its reply is graded.");

    /** Value of {@link ModelConfig#apiKeyEnv()} meaning "send no key", for local servers. */
    public static final String NO_KEY = "none";

    private final String id;
    private final String label;
    private final ModelConfig.Provider provider;
    private final String baseUrl;
    private final String keyEnv;
    private final List<String> exampleModels;
    private final int requestsPerMinute;
    private final boolean local;
    private final String note;

    ProviderPreset(String id, String label, ModelConfig.Provider provider, String baseUrl, String keyEnv,
                   List<String> exampleModels, int requestsPerMinute, boolean local, String note) {
        this.id = id;
        this.label = label;
        this.provider = provider;
        this.baseUrl = baseUrl;
        this.keyEnv = keyEnv;
        this.exampleModels = exampleModels;
        this.requestsPerMinute = requestsPerMinute;
        this.local = local;
        this.note = note;
    }

    public String id() {
        return id;
    }

    public String label() {
        return label;
    }

    public ModelConfig.Provider provider() {
        return provider;
    }

    public String baseUrl() {
        return baseUrl;
    }

    /** The key variable, or null when no key is needed. */
    public String keyEnv() {
        return keyEnv;
    }

    public List<String> exampleModels() {
        return exampleModels;
    }

    /** Provider-side request limit to stay under, or 0 for none beyond the global limit. */
    public int requestsPerMinute() {
        return requestsPerMinute;
    }

    public boolean local() {
        return local;
    }

    public String note() {
        return note;
    }

    /** A config for {@code model} on this preset. */
    public ModelConfig config(String model) {
        return config(model, baseUrl);
    }

    public ModelConfig config(String model, String url) {
        return new ModelConfig(provider, model, url, keyEnv == null ? NO_KEY : keyEnv, null, null, null, null, null);
    }

    public static Optional<ProviderPreset> byId(String id) {
        return Arrays.stream(values()).filter(p -> p.id.equalsIgnoreCase(id)).findFirst();
    }

    /** The preset whose base URL matches, used to apply its request rate to hand-written configs. */
    public static Optional<ProviderPreset> forBaseUrl(String url) {
        if (url == null) {
            return Optional.empty();
        }
        String normalized = trimSlash(url);
        return Arrays.stream(values()).filter(p -> p.baseUrl != null && trimSlash(p.baseUrl).equals(normalized)).findFirst();
    }

    /** True for servers on this machine, which cost nothing to call. */
    public static boolean isLocalUrl(String url) {
        try {
            String host = URI.create(url.trim()).getHost();
            if (host == null) {
                return false;
            }
            host = host.toLowerCase(Locale.ROOT);
            return host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]") || host.equals("::1");
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String trimSlash(String s) {
        String t = s.trim();
        return t.endsWith("/") ? t.substring(0, t.length() - 1) : t;
    }

    /**
     * Parses a CLI or {@code PF_DEFAULT_MODEL} model spec: a bare id is an Anthropic model, otherwise
     * {@code <preset>:<model>} such as {@code gemini:gemini-2.5-flash}, {@code groq:llama-3.3-70b-versatile} or
     * {@code ollama:qwen2.5:7b}. Any OpenAI-compatible server: {@code openai:<model>@<base-url>}, optionally
     * followed by {@code #<KEY_ENV>} (or {@code #none}).
     */
    public static ModelConfig parseSpec(String spec) {
        String s = spec.trim();
        int colon = s.indexOf(':');
        Optional<ProviderPreset> preset = colon > 0 ? byId(s.substring(0, colon)) : Optional.empty();
        if (preset.isEmpty() || preset.get() == ENDPOINT) {
            return ModelConfig.anthropic(s);
        }
        String rest = s.substring(colon + 1);
        if (preset.get() != OPENAI) {
            return preset.get().config(rest);
        }
        String keyEnv = OPENAI.keyEnv;
        int hash = rest.lastIndexOf('#');
        if (hash >= 0) {
            keyEnv = rest.substring(hash + 1);
            rest = rest.substring(0, hash);
        }
        int at = rest.indexOf('@');
        String model = at < 0 ? rest : rest.substring(0, at);
        String url = at < 0 ? OPENAI.baseUrl : rest.substring(at + 1);
        return new ModelConfig(OPENAI.provider, model, url, keyEnv, null, null, null, null, null);
    }
}
