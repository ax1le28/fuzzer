package io.promptfuzzer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import io.promptfuzzer.core.Json;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * A local OpenAI-compatible server for end-to-end tests. It decides what to answer from the request, so the
 * generator, the target and the judge can all be exercised over real HTTP without network access or cost.
 */
public final class MockLlmServer implements AutoCloseable {

    private final HttpServer server;
    private final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    private final AtomicInteger rateLimitsLeft;

    /** @param responder maps (system prompt, user message) to the reply text */
    public MockLlmServer(Responder responder, int initialRateLimits) throws IOException {
        this.rateLimitsLeft = new AtomicInteger(initialRateLimits);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            JsonNode body = Json.parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            requests.add(body);
            byte[] out;
            int status = 200;
            if (rateLimitsLeft.getAndDecrement() > 0) {
                status = 429;
                exchange.getResponseHeaders().add("retry-after", "0");
                out = "{\"error\": \"rate limited\"}".getBytes(StandardCharsets.UTF_8);
            } else {
                String system = "";
                String user = "";
                for (JsonNode m : body.path("messages")) {
                    if (m.path("role").asText().equals("system")) {
                        system = m.path("content").asText();
                    } else {
                        user = m.path("content").asText();
                    }
                }
                ObjectNode reply = Json.MAPPER.createObjectNode();
                reply.putArray("choices").addObject().put("finish_reason", "stop")
                        .putObject("message").put("role", "assistant").put("content", responder.reply(system, user));
                reply.putObject("usage").put("prompt_tokens", 120).put("completion_tokens", 30);
                out = Json.write(reply).getBytes(StandardCharsets.UTF_8);
            }
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    public List<JsonNode> requests() {
        return requests;
    }

    @Override
    public void close() {
        server.stop(0);
    }

    @FunctionalInterface
    public interface Responder {
        String reply(String system, String user);

        static Responder routing(Function<String, String> generator, Function<String, String> judge, Function<String, String> target) {
            return (system, user) -> {
                if (system.contains("You design test cases")) {
                    return generator.apply(user);
                }
                if (system.contains("impartial evaluator")) {
                    return judge.apply(user);
                }
                if (system.contains("senior AI engineer")) {
                    return "{\"summary\": \"Negations are ignored.\", \"recommendations\": [{\"title\": \"Add negation examples\", \"detail\": \"Show turn_off cases.\", \"addresses\": [\"NEGATION_MISUNDERSTANDING\"]}], \"prompt_additions\": \"Pay attention to 'don't'.\"}";
                }
                return target.apply(user);
            };
        }
    }
}
