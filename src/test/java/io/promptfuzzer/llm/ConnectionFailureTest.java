package io.promptfuzzer.llm;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Diagnostics and fail-fast behaviour for servers that cannot be reached. Uses only loopback sockets. */
class ConnectionFailureTest {

    private static final ChatRequest REQUEST = new ChatRequest("sys", "hi", 100);

    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    @Test
    void refusedConnectionNamesTheExceptionAndTheFix() throws IOException {
        String base = "http://127.0.0.1:" + closedPort() + "/v1";
        LlmException e = assertThrows(LlmException.class,
                () -> new OpenAiCompatibleChatModel(base, null, "m", null).complete(REQUEST));
        assertTrue(e instanceof LlmException.ConnectionFailedException, e.getClass().toString());
        assertFalse(e.retryable(), "a refused connection is not worth a backoff loop");
        assertTrue(e.getMessage().contains("ConnectException"), e.getMessage());
        assertFalse(e.getMessage().contains("null"), e.getMessage());
        assertTrue(e.getMessage().contains("Check that the local server is running"), e.getMessage());
    }

    @Test
    void describeNeverPrintsNull() {
        assertEquals("ConnectException", LlmException.describe(new ConnectException()));
        assertEquals("IOException caused by IllegalStateException: boom",
                LlmException.describe(new IOException((String) null, new IllegalStateException("boom"))));
        assertEquals("unknown error", LlmException.describe(null));
    }

    @Test
    void hintsAreSpecificToTheServer() {
        assertTrue(LlmException.connectionHint("http://localhost:11434/v1").contains("ollama serve"));
        assertTrue(LlmException.connectionHint("http://127.0.0.1:1234/v1").contains("local server is running"));
        assertTrue(LlmException.connectionHint("https://api.groq.com/openai/v1").contains("network connection"));
    }

    @Test
    void stopsCallingAnEndpointAfterRepeatedConnectionFailures() {
        AtomicInteger calls = new AtomicInteger();
        ChatModel down = new ChatModel() {
            @Override
            public String id() {
                return "down";
            }

            @Override
            public Optional<Pricing> pricing() {
                return Optional.empty();
            }

            @Override
            public ChatResponse complete(ChatRequest request) {
                calls.incrementAndGet();
                throw LlmException.connectionFailed("http://localhost:11434/v1/chat/completions", new ConnectException());
            }
        };
        Budget budget = new Budget(100, 1.0);
        List<Long> sleeps = new CopyOnWriteArrayList<>();
        ResilientChatModel model = new ResilientChatModel(down, null, budget, ResilientChatModel.RetryPolicy.DEFAULT, sleeps::add);

        for (int i = 0; i < ResilientChatModel.CONNECTION_FAILURE_LIMIT; i++) {
            assertThrows(LlmException.ConnectionFailedException.class, () -> model.complete(REQUEST));
        }
        assertEquals(ResilientChatModel.CONNECTION_FAILURE_LIMIT, calls.get(), "one attempt per call, no retries");
        assertTrue(sleeps.isEmpty(), "no backoff waits: " + sleeps);

        ResilientChatModel.EndpointUnavailableException open =
                assertThrows(ResilientChatModel.EndpointUnavailableException.class, () -> model.complete(REQUEST));
        assertEquals(ResilientChatModel.CONNECTION_FAILURE_LIMIT, calls.get(), "the endpoint is no longer called");
        assertEquals(ResilientChatModel.CONNECTION_FAILURE_LIMIT, budget.requestsUsed(), "and no request is charged");
        assertEquals("http://localhost:11434", open.endpoint());
        assertTrue(open.getMessage().contains("ollama serve"), open.getMessage());
    }

    @Test
    void preflightReportsAMissingModelWithoutSendingTestData() throws IOException {
        List<String> seen = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            seen.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath() + " " + body.length);
            byte[] out = "{\"object\":\"list\",\"data\":[{\"id\":\"llama3.1:8b\"},{\"id\":\"qwen2.5:latest\"}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            Optional<String> missing = LocalServerCheck.problem(ProviderPreset.OLLAMA.config("qwen2.5:7b", base));
            assertTrue(missing.isPresent());
            assertTrue(missing.get().contains("'qwen2.5:7b' is not available"), missing.get());
            assertTrue(missing.get().contains("installed: llama3.1:8b, qwen2.5:latest"), missing.get());
            assertFalse(missing.get().contains("not running"), "a missing model is not reported as a stopped server");

            assertTrue(LocalServerCheck.problem(ProviderPreset.OLLAMA.config("llama3.1:8b", base)).isEmpty());
            assertTrue(LocalServerCheck.problem(ProviderPreset.OLLAMA.config("qwen2.5", base)).isEmpty(), "untagged means :latest");

            assertEquals(3, seen.size());
            seen.forEach(s -> assertEquals("GET /v1/models 0", s, "only the model list is requested; no prompt is sent"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void preflightOnlyProbesLocalOpenAiCompatibleServers() {
        assertFalse(LocalServerCheck.applies(ProviderPreset.GROQ.config("llama")));
        assertFalse(LocalServerCheck.applies(ModelConfig.anthropic("claude-haiku-5-5")));
        assertTrue(LocalServerCheck.applies(ProviderPreset.OLLAMA.config("qwen2.5:7b")));
    }
}
