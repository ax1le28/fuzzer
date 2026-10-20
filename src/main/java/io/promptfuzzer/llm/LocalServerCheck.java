package io.promptfuzzer.llm;

import com.fasterxml.jackson.databind.JsonNode;
import io.promptfuzzer.core.Json;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A quick reachability check for OpenAI-compatible servers on this machine (Ollama, LM Studio, llama.cpp),
 * run before a generation or a test run starts. It asks only for the server's model list
 * ({@code GET <base>/models}), so no system prompt or test data leaves the application.
 *
 * <p>It reports separately whether the server is not running and whether the chosen model is missing. Servers
 * that do not list models are given the benefit of the doubt.
 */
public final class LocalServerCheck {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private LocalServerCheck() {
    }

    /** True for configs this check applies to: OpenAI-compatible servers on a loopback address. */
    public static boolean applies(ModelConfig config) {
        return config != null && config.provider() == ModelConfig.Provider.OPENAI_COMPATIBLE
                && config.baseUrl() != null && ProviderPreset.isLocalUrl(config.baseUrl());
    }

    /** A user-facing description of why {@code config} cannot be used right now, or empty if it looks fine. */
    public static Optional<String> problem(ModelConfig config) {
        if (!applies(config)) {
            return Optional.empty();
        }
        String base = config.baseUrl().trim().replaceAll("/+$", "");
        boolean ollama = LlmException.isOllama(base);
        String server = ollama ? "Ollama" : "The local server";
        HttpResponse<String> response;
        try {
            response = HttpSupport.get(base + "/models", TIMEOUT);
        } catch (LlmException.ConnectionFailedException e) {
            return Optional.of(server + " is not running at " + LlmException.origin(base) + " ("
                    + LlmException.describe(e.getCause()) + "). " + LlmException.connectionHint(base));
        } catch (LlmException e) {
            return Optional.of(server + " at " + LlmException.origin(base) + " did not answer a model-list request: "
                    + e.getMessage() + ". " + LlmException.connectionHint(base));
        }
        if (response.statusCode() / 100 != 2) {
            return Optional.empty(); // reachable, but does not list models: let the run find out
        }
        List<String> installed = modelIds(response.body());
        if (installed == null || config.model() == null || matches(config.model(), installed)) {
            return Optional.empty();
        }
        String list = installed.isEmpty() ? "none" : String.join(", ", installed);
        return Optional.of("Model '" + config.model() + "' is not available on " + (ollama ? "Ollama" : "the local server")
                + " at " + LlmException.origin(base) + " (installed: " + list + "). "
                + (ollama ? "Download it with `ollama pull " + config.model() + "`, or pick an installed model."
                        : "Load that model in the server, or pick one it lists."));
    }

    /** Model ids from an OpenAI-style {@code {"data": [{"id": ...}]}} body, or null if the body has another shape. */
    static List<String> modelIds(String body) {
        JsonNode data;
        try {
            data = Json.parse(body).path("data");
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (!data.isArray()) {
            return null;
        }
        List<String> ids = new ArrayList<>();
        data.forEach(m -> {
            String id = m.path("id").asText("");
            if (!id.isBlank()) {
                ids.add(id);
            }
        });
        return ids;
    }

    /** Ollama resolves an untagged name to {@code :latest}, so {@code llama3} matches {@code llama3:latest}. */
    static boolean matches(String model, List<String> installed) {
        String wanted = model.trim();
        return installed.stream().anyMatch(id -> id.equals(wanted)
                || (!wanted.contains(":") && id.equals(wanted + ":latest")));
    }
}
