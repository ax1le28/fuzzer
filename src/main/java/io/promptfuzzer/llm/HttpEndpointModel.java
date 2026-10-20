package io.promptfuzzer.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.promptfuzzer.core.Json;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Tests a whole AI application rather than a bare model: each test input is POSTed to the user's own endpoint
 * as {@code {"input": "..."}} and the reply is read from the body (optionally at a JSON pointer such as
 * {@code /output}).
 *
 * <p>The task's system prompt is not sent, because the application owns its own instructions.
 */
public final class HttpEndpointModel implements ChatModel {

    private final String name;
    private final String url;
    private final String authHeaderValue;
    private final String responsePointer;

    public HttpEndpointModel(String name, String url, String authHeaderValue, String responsePointer) {
        this.name = name;
        this.url = url;
        this.authHeaderValue = authHeaderValue;
        this.responsePointer = responsePointer;
    }

    @Override
    public String id() {
        return "endpoint:" + name;
    }

    @Override
    public Optional<Pricing> pricing() {
        return Optional.empty();
    }

    @Override
    public ChatResponse complete(ChatRequest request) {
        ObjectNode body = Json.MAPPER.createObjectNode().put("input", request.user());
        Map<String, String> headers = new HashMap<>();
        if (authHeaderValue != null && !authHeaderValue.isBlank()) {
            headers.put("Authorization", authHeaderValue);
        }
        HttpSupport.Result result = HttpSupport.postJson(url, Json.write(body), headers, Duration.ofMinutes(2));
        return new ChatResponse(extract(result.body(), responsePointer), 0, 0, result.latencyMs(), "end_turn");
    }

    static String extract(String body, String pointer) {
        if (pointer == null || pointer.isBlank()) {
            return body;
        }
        JsonNode node;
        try {
            node = Json.parse(body).at(pointer);
        } catch (IllegalArgumentException e) {
            throw LlmException.fatal("Endpoint did not return JSON, cannot read " + pointer);
        }
        if (node.isMissingNode()) {
            throw LlmException.fatal("Endpoint response has nothing at " + pointer);
        }
        return node.isTextual() ? node.asText() : Json.write(node);
    }
}
