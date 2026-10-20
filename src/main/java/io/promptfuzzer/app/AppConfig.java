package io.promptfuzzer.app;

import io.promptfuzzer.llm.ProviderPreset;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;

/**
 * Runtime settings, read from environment variables so nothing sensitive lives in files.
 *
 * @param maxCostUsd       hard ceiling on estimated spend for any single generation or run
 * @param maxRequests      hard ceiling on API requests for any single generation or run
 * @param maxTests         largest suite that may be generated or run
 * @param requestsPerMinute client-side rate limit per provider
 * @param defaultModel     default generator, judge and target model spec (see {@link ProviderPreset#parseSpec});
 *                         without {@code PF_DEFAULT_MODEL}, the first of Anthropic, Gemini and Groq with a key set
 */
public record AppConfig(
        String host,
        int port,
        Path dataDir,
        double maxCostUsd,
        int maxRequests,
        int maxTests,
        int requestsPerMinute,
        String defaultModel) {

    public static AppConfig fromEnv(Function<String, String> env) {
        return new AppConfig(
                get(env, "PF_HOST", "127.0.0.1"),
                Integer.parseInt(get(env, "PF_PORT", "7070")),
                Path.of(get(env, "PF_DATA_DIR", "promptfuzzer-data")),
                Double.parseDouble(get(env, "PF_MAX_COST_USD", "2.00")),
                Integer.parseInt(get(env, "PF_MAX_REQUESTS", "600")),
                Integer.parseInt(get(env, "PF_MAX_TESTS", "150")),
                Integer.parseInt(get(env, "PF_REQUESTS_PER_MINUTE", "50")),
                get(env, "PF_DEFAULT_MODEL", defaultModel(env)));
    }

    private static String defaultModel(Function<String, String> env) {
        for (ProviderPreset p : List.of(ProviderPreset.ANTHROPIC, ProviderPreset.GEMINI, ProviderPreset.GROQ)) {
            String key = env.apply(p.keyEnv());
            if (key != null && !key.isBlank()) {
                String model = p.exampleModels().get(0);
                return p == ProviderPreset.ANTHROPIC ? model : p.id() + ":" + model;
            }
        }
        return ProviderPreset.ANTHROPIC.exampleModels().get(0);
    }

    private static String get(Function<String, String> env, String key, String fallback) {
        String v = env.apply(key);
        return v == null || v.isBlank() ? fallback : v.trim();
    }
}
