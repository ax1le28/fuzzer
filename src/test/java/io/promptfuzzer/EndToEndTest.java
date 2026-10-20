package io.promptfuzzer;

import com.fasterxml.jackson.databind.JsonNode;
import io.javalin.Javalin;
import io.promptfuzzer.app.AppConfig;
import io.promptfuzzer.app.FileStore;
import io.promptfuzzer.app.GenerationService;
import io.promptfuzzer.app.ModelProvider;
import io.promptfuzzer.app.RunService;
import io.promptfuzzer.analysis.RunReport;
import io.promptfuzzer.core.Json;
import io.promptfuzzer.core.TestSuite;
import io.promptfuzzer.llm.ModelFactory;
import io.promptfuzzer.web.WebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the whole workflow through the HTTP API against a local OpenAI-compatible mock: generate a suite,
 * estimate, run with retries on a 429, poll progress, and read the analysed report.
 */
class EndToEndTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @TempDir
    Path dataDir;

    private MockLlmServer llm;
    private Javalin server;
    private String base;

    @BeforeEach
    void start() throws Exception {
        llm = new MockLlmServer(MockLlmServer.Responder.routing(
                user -> """
                        {"cases": [
                          {"seed_index": 0, "input": "Don't turn on the kitchen lights, turn them off", "expected_behavior": "MATCH_EXPECTED",
                           "expected_output": {"action": "turn_off", "device": "lights", "room": "kitchen"}, "rationale": "negation"},
                          {"seed_index": 0, "input": "Turn on the lights", "expected_behavior": "ASK_CLARIFICATION", "rationale": "no room"}
                        ]}""",
                user -> "{\"verdict\": \"pass\", \"confidence\": 0.8, \"reasoning\": \"ok\"}",
                EndToEndTest::naiveTarget), 1);

        Map<String, String> env = Map.of("OPENAI_API_KEY", "test-key", "PF_PORT", "0",
                "PF_DATA_DIR", dataDir.toString(), "PF_REQUESTS_PER_MINUTE", "6000");
        AppConfig config = AppConfig.fromEnv(env::get);
        ModelFactory factory = ModelFactory.fromMap(env);
        ModelProvider provider = new ModelProvider(factory, config.requestsPerMinute());
        FileStore<TestSuite> suites = new FileStore<>(dataDir.resolve("suites"), TestSuite.class);
        FileStore<RunReport> runs = new FileStore<>(dataDir.resolve("runs"), RunReport.class);
        server = new WebServer(config, factory, suites, new GenerationService(config, provider, suites),
                new RunService(config, provider, suites, runs)).start();
        base = "http://127.0.0.1:" + server.port();
    }

    @AfterEach
    void stop() {
        server.stop();
        llm.close();
    }

    /** A deliberately imperfect "model": keyword matching that ignores negation. */
    static String naiveTarget(String input) {
        String s = input.toLowerCase();
        if (s.contains("kitchen")) {
            return "{\"action\": \"turn_on\", \"device\": \"lights\", \"room\": \"kitchen\"}";
        }
        if (s.contains("thermostat")) {
            return "{\"action\": \"set\", \"device\": \"thermostat\", \"room\": \"bedroom\", \"value\": 21}";
        }
        if (s.contains("door")) {
            return "```json\n{\"action\": \"lock\", \"device\": \"door_lock\", \"room\": \"hallway\"}\n```";
        }
        if (s.contains("blinds")) {
            return "{\"action\": \"set\", \"device\": \"blinds\", \"room\": \"living_room\", \"value\": 50}";
        }
        if (s.contains("speaker")) {
            return "{\"action\": \"set\", \"device\": \"speaker\", \"room\": \"office\", \"value\": 20}";
        }
        return "{\"action\": \"clarify\", \"question\": \"Which room?\"}";
    }

    @Test
    void fullWorkflowOverHttp() throws Exception {
        JsonNode status = get("/api/status");
        assertTrue(status.path("providers").path("OPENAI_COMPATIBLE").asBoolean());
        assertFalse(status.path("providers").path("ANTHROPIC").asBoolean());
        assertFalse(Json.write(status).contains("test-key"), "keys are never exposed");

        JsonNode task = get("/api/examples").get(0);
        Map<String, Object> mockModel = Map.of("provider", "OPENAI_COMPATIBLE", "model", "mock-model", "baseUrl", llm.baseUrl());
        JsonNode suite = post("/api/suites/generate", Map.of("task", task, "generator", mockModel,
                "categories", List.of("NEGATION", "AMBIGUITY"), "perCategory", 2, "includeRules", true, "seed", 42), 200);
        String suiteId = suite.path("id").asText();
        // 20 rule-based + 2 unique AI cases (both batches return the same two inputs; duplicates are removed).
        assertEquals(22, suite.path("cases").size(), Json.write(suite.path("generation")));

        Map<String, Object> run = Map.of("suiteId", suiteId, "targets", List.of(mockModel), "repeats", 1,
                "concurrency", 4, "judge", mockModel, "aiAnalysis", true, "maxCostUsd", 1.0);
        JsonNode estimate = post("/api/runs/estimate", run, 200);
        assertEquals(22, estimate.path("calls").asInt());

        String runId = post("/api/runs", run, 202).path("id").asText();
        JsonNode report = null;
        for (int i = 0; i < 150; i++) {
            report = get("/api/runs/" + runId + "?results=true");
            if (!report.path("status").asText().equals("RUNNING")) {
                break;
            }
            Thread.sleep(100);
        }
        assertNotNull(report);
        assertEquals("COMPLETED", report.path("status").asText(), Json.write(report.path("message")));
        assertEquals(22, report.path("results").size());

        JsonNode summary = report.path("models").get(0);
        assertEquals("mock-model", summary.path("model").asText());
        assertEquals(0, summary.path("errors").asInt(), "the injected 429 was retried");
        assertTrue(summary.path("failed").asInt() > 0);

        List<String> clusters = new ArrayList<>();
        report.path("analysis").path("clusters").forEach(c -> clusters.add(c.path("type").asText()));
        assertTrue(clusters.contains("NEGATION_MISUNDERSTANDING"), clusters.toString());
        assertTrue(clusters.contains("FORMATTING"), clusters.toString());
        assertEquals("Negations are ignored.", report.path("analysis").path("aiInsights").path("summary").asText());

        // Target calls carry the task's system prompt and the test input as the user message.
        assertTrue(llm.requests().stream().anyMatch(r -> r.path("messages").get(0).path("content").asText()
                .startsWith("You are the command parser")));

        JsonNode list = get("/api/runs");
        assertEquals(runId, list.get(0).path("id").asText());
        assertEquals(0, list.get(0).path("results").size(), "listing omits per-test results");
    }

    /** The free/local path: no API key at all, every model call goes to a keyless local server. */
    @Test
    void cliWorksEndToEndWithoutAnyApiKey(@TempDir Path dir) throws Exception {
        java.nio.file.Files.writeString(dir.resolve("task.json"), Json.write(TestFixtures.smartHome()));
        String model = "openai:local-model@" + llm.baseUrl() + "#none";
        java.io.ByteArrayOutputStream log = new java.io.ByteArrayOutputStream();
        java.io.PrintStream out = new java.io.PrintStream(log, true);
        Map<String, String> noKeys = Map.of("PF_DATA_DIR", dir.toString(), "PF_REQUESTS_PER_MINUTE", "6000");

        assertEquals(0, io.promptfuzzer.cli.CliAccess.run(noKeys, out, new String[]{"generate", "--task", dir.resolve("task.json").toString(),
                "--out", dir.resolve("suite.json").toString(), "--model", model, "--categories", "NEGATION", "--per-category", "2"}), log.toString());
        assertEquals(1, io.promptfuzzer.cli.CliAccess.run(noKeys, out, new String[]{"run", "--suite", dir.resolve("suite.json").toString(),
                "--model", model, "--judge", model, "--out", dir.resolve("report.json").toString(), "--min-pass-rate", "0.99"}),
                "the naive local model fails the gate: " + log);

        RunReport report = Json.read(dir.resolve("report.json"), RunReport.class);
        assertEquals(22, report.results().size());
        assertTrue(report.results().stream().allMatch(r -> r.error() == null), "no call needed a key");
        assertTrue(llm.authorizations().stream().allMatch("null"::equals), "no Authorization header was ever sent");
        assertEquals(0.0, report.models().get(0).costUsd(), "a local server is priced at zero");
    }

    @Test
    void ruleBasedGenerationNeedsNoModel() throws Exception {
        JsonNode task = get("/api/examples").get(1);
        JsonNode suite = post("/api/suites/generate", Map.of("task", task, "categories", List.of(), "perCategory", 0,
                "includeRules", true), 200);
        assertEquals(12, suite.path("cases").size());
        assertTrue(llm.requests().isEmpty());
    }

    @Test
    void demoIsAvailableWithoutKeys() throws Exception {
        JsonNode demo = get("/api/demo");
        assertTrue(demo.path("demo").asBoolean());
        assertTrue(demo.path("message").asText().contains("Sample data"));
    }

    @Test
    void rejectsCrossOriginAndNonJsonWrites() throws Exception {
        HttpResponse<String> crossOrigin = HTTP.send(HttpRequest.newBuilder(URI.create(base + "/api/suites/generate"))
                .header("Content-Type", "application/json").header("Origin", "https://evil.example")
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(403, crossOrigin.statusCode());

        HttpResponse<String> formPost = HTTP.send(HttpRequest.newBuilder(URI.create(base + "/api/runs"))
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(403, formPost.statusCode());
    }

    @Test
    void validationErrorsAreClientErrors() throws Exception {
        post("/api/runs", Map.of("suiteId", "does-not-exist", "targets", List.of(), "repeats", 1, "concurrency", 1), 400);
        HttpResponse<String> traversal = HTTP.send(HttpRequest.newBuilder(URI.create(base + "/api/suites/..%2F..%2Fetc%2Fpasswd"))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(404, traversal.statusCode());
    }

    @Test
    void staticDashboardIsServed() throws Exception {
        HttpResponse<String> index = HTTP.send(HttpRequest.newBuilder(URI.create(base + "/")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, index.statusCode());
        assertTrue(index.body().contains("<title>PromptFuzzer</title>"));
    }

    private JsonNode get(String path) throws Exception {
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(20)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode(), r.body());
        return Json.parse(r.body());
    }

    private JsonNode post(String path, Object body, int expectedStatus) throws Exception {
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body))).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(expectedStatus, r.statusCode(), r.body());
        return Json.parse(r.body());
    }
}
