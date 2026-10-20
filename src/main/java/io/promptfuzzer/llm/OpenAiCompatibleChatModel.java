package io.promptfuzzer.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.promptfuzzer.core.Json;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Any server that speaks the widely used {@code /chat/completions} protocol: OpenAI, Ollama, LM Studio, vLLM,
 * llama.cpp and most hosted gateways. Lets the same suite run against local and non-Anthropic models.
 */
public final class OpenAiCompatibleChatModel implements ChatModel {

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final Pricing pricing;

    public OpenAiCompatibleChatModel(String baseUrl, String apiKey, String model, Pricing pricing) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.pricing = pricing;
    }

    @Override
    public String id() {
        return model;
    }

    @Override
    public Optional<Pricing> pricing() {
        return Optional.ofNullable(pricing);
    }

    @Override
    public ChatResponse complete(ChatRequest request) {
        ObjectNode body = Json.MAPPER.createObjectNode();
        body.put("model", model);
        body.put("max_tokens", request.maxTokens());
        ArrayNode messages = body.putArray("messages");
        if (request.system() != null && !request.system().isBlank()) {
            messages.addObject().put("role", "system").put("content", request.system());
        }
        messages.addObject().put("role", "user").put("content", request.user());

        Map<String, String> headers = new HashMap<>();
        if (apiKey != null && !apiKey.isBlank()) {
            headers.put("Authorization", "Bearer " + apiKey);
        }
        HttpSupport.Result result = HttpSupport.postJson(
                baseUrl + "/chat/completions", Json.write(body), headers, Duration.ofMinutes(5));
        return parse(result.body(), result.latencyMs());
    }

    static ChatResponse parse(String body, long latencyMs) {
        JsonNode root;
        try {
            root = Json.parse(body);
        } catch (IllegalArgumentException e) {
            throw LlmException.fatal("Provider returned non-JSON body: " + HttpSupport.abbreviate(body));
        }
        JsonNode choice = root.path("choices").path(0);
        if (choice.isMissingNode()) {
            throw LlmException.fatal("Provider response has no choices: " + HttpSupport.abbreviate(body));
        }
        String text = choice.path("message").path("content").asText("");
        String finish = choice.path("finish_reason").asText(null);
        String stop = switch (finish == null ? "" : finish) {
            case "length" -> ChatResponse.MAX_TOKENS;
            case "content_filter" -> ChatResponse.REFUSAL;
            case "" -> null;
            default -> finish;
        };
        JsonNode usage = root.path("usage");
        return new ChatResponse(text,
                usage.path("prompt_tokens").asLong(0),
                usage.path("completion_tokens").asLong(0),
                latencyMs, stop);
    }
}
