package io.promptfuzzer.web;

import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import io.javalin.http.staticfiles.Location;
import io.javalin.json.JavalinJackson;
import io.promptfuzzer.analysis.RunReport;
import io.promptfuzzer.app.AppConfig;
import io.promptfuzzer.app.DemoBuilder;
import io.promptfuzzer.app.GenerationService;
import io.promptfuzzer.app.RunService;
import io.promptfuzzer.core.Json;
import io.promptfuzzer.core.TaskSpec;
import io.promptfuzzer.core.TestCase;
import io.promptfuzzer.core.TestCategory;
import io.promptfuzzer.core.TestSuite;
import io.promptfuzzer.app.FileStore;
import io.promptfuzzer.llm.LlmException;
import io.promptfuzzer.llm.ModelConfig;
import io.promptfuzzer.llm.ModelFactory;
import io.promptfuzzer.llm.Pricing;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The local HTTP API and dashboard. */
public final class WebServer {

    private static final List<String> EXAMPLES = List.of("smart-home-commands", "calendar-events");
    private static final Set<String> LOOPBACK = Set.of("localhost", "127.0.0.1", "[::1]");

    private final AppConfig config;
    private final ModelFactory factory;
    private final FileStore<TestSuite> suites;
    private final GenerationService generation;
    private final RunService runs;
    private RunReport demoReport;

    public WebServer(AppConfig config, ModelFactory factory, FileStore<TestSuite> suites,
                     GenerationService generation, RunService runs) {
        this.config = config;
        this.factory = factory;
        this.suites = suites;
        this.generation = generation;
        this.runs = runs;
    }

    public Javalin start() {
        Javalin app = Javalin.create(cfg -> {
            cfg.showJavalinBanner = false;
            cfg.jsonMapper(new JavalinJackson(Json.MAPPER, false));
            cfg.staticFiles.add(sf -> {
                sf.hostedPath = "/";
                sf.directory = "/web";
                sf.location = Location.CLASSPATH;
            });
            cfg.http.maxRequestSize = 2_000_000L;
        });

        app.before("/api/*", this::guard);

        app.get("/api/status", ctx -> ctx.json(status()));
        app.get("/api/categories", ctx -> ctx.json(categories()));
        app.get("/api/examples", ctx -> ctx.json(examples()));
        app.get("/api/demo", ctx -> ctx.json(demo()));

        app.get("/api/suites", ctx -> ctx.json(suites.list().stream().map(WebServer::suiteSummary).toList()));
        app.get("/api/suites/{id}", ctx -> ctx.json(suites.load(ctx.pathParam("id"))
                .orElseThrow(() -> new NotFound("Suite not found"))));
        app.post("/api/suites/generate", ctx -> ctx.json(
                generation.generate(ctx.bodyAsClass(GenerationService.Request.class))));
        app.put("/api/suites/{id}", ctx -> ctx.json(updateSuite(ctx.pathParam("id"), ctx.bodyAsClass(TestSuite.class))));
        app.delete("/api/suites/{id}", ctx -> ctx.json(Map.of("deleted", suites.delete(ctx.pathParam("id")))));

        app.post("/api/runs/estimate", ctx -> ctx.json(runs.estimate(ctx.bodyAsClass(RunService.Request.class))));
        app.post("/api/runs", ctx -> ctx.status(HttpStatus.ACCEPTED).json(runs.start(ctx.bodyAsClass(RunService.Request.class))));
        app.get("/api/runs", ctx -> ctx.json(runs.list()));
        app.get("/api/runs/{id}", ctx -> ctx.json(runs.get(ctx.pathParam("id"), "true".equals(ctx.queryParam("results")))
                .orElseThrow(() -> new NotFound("Run not found"))));
        app.post("/api/runs/{id}/cancel", ctx -> ctx.json(Map.of("cancelled", runs.cancel(ctx.pathParam("id")))));
        app.delete("/api/runs/{id}", ctx -> ctx.json(Map.of("deleted", runs.delete(ctx.pathParam("id")))));

        app.exception(NotFound.class, (e, ctx) -> error(ctx, HttpStatus.NOT_FOUND, e.getMessage()));
        app.exception(Forbidden.class, (e, ctx) -> error(ctx, HttpStatus.FORBIDDEN, e.getMessage()));
        app.exception(IllegalArgumentException.class, (e, ctx) -> error(ctx, HttpStatus.BAD_REQUEST, e.getMessage()));
        app.exception(IllegalStateException.class, (e, ctx) -> error(ctx, HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage()));
        app.exception(io.javalin.http.BadRequestResponse.class, (e, ctx) -> error(ctx, HttpStatus.BAD_REQUEST, e.getMessage()));
        app.exception(LlmException.class, (e, ctx) -> error(ctx, HttpStatus.BAD_GATEWAY, e.getMessage()));
        app.exception(Exception.class, (e, ctx) -> {
            e.printStackTrace();
            error(ctx, HttpStatus.INTERNAL_SERVER_ERROR, "Internal error: " + e.getClass().getSimpleName());
        });

        return app.start(config.host(), config.port());
    }

    /**
     * Protects a server that can spend money from other web pages open in the same browser: rejects requests
     * for foreign Host names (DNS rebinding) and cross-origin or non-JSON writes (CSRF).
     */
    private void guard(Context ctx) {
        boolean loopbackBind = LOOPBACK.contains(config.host()) || config.host().startsWith("127.");
        String host = ctx.host() == null ? "" : ctx.host().replaceAll(":\\d+$", "");
        if (loopbackBind && !LOOPBACK.contains(host) && !host.startsWith("127.")) {
            throw new Forbidden("Unexpected Host header");
        }
        String method = ctx.method().name();
        if (method.equals("GET") || method.equals("HEAD") || method.equals("OPTIONS")) {
            return;
        }
        String origin = ctx.header("Origin");
        if (origin != null && !origin.equals("http://" + ctx.host()) && !origin.equals("https://" + ctx.host())) {
            throw new Forbidden("Cross-origin request rejected");
        }
        String type = ctx.contentType();
        if (!method.equals("DELETE") && (type == null || !type.startsWith("application/json"))) {
            throw new Forbidden("Content-Type must be application/json");
        }
    }

    private Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("version", "0.1.0");
        out.put("providers", Map.of(
                "ANTHROPIC", factory.hasKey(ModelConfig.Provider.ANTHROPIC),
                "OPENAI_COMPATIBLE", factory.hasKey(ModelConfig.Provider.OPENAI_COMPATIBLE)));
        out.put("defaultModel", config.defaultModel());
        List<Map<String, Object>> models = new ArrayList<>();
        Pricing.known().entrySet().stream()
                .sorted(Map.Entry.comparingByValue((a, b) -> Double.compare(b.inputPerMTok(), a.inputPerMTok())))
                .forEach(e -> models.add(Map.of("id", e.getKey(), "inputPerMTok", e.getValue().inputPerMTok(),
                        "outputPerMTok", e.getValue().outputPerMTok())));
        out.put("knownModels", models);
        out.put("limits", Map.of("maxCostUsd", config.maxCostUsd(), "maxRequests", config.maxRequests(),
                "maxTests", config.maxTests(), "requestsPerMinute", config.requestsPerMinute()));
        return out;
    }

    private static List<Map<String, String>> categories() {
        return Arrays.stream(TestCategory.values())
                .map(c -> Map.of("id", c.name(), "name", c.displayName(), "description", c.description()))
                .toList();
    }

    private static List<TaskSpec> examples() {
        List<TaskSpec> out = new ArrayList<>();
        for (String name : EXAMPLES) {
            out.add(Json.read(resource("/examples/" + name + ".json"), TaskSpec.class));
        }
        return out;
    }

    private synchronized RunReport demo() {
        if (demoReport == null) {
            demoReport = DemoBuilder.build();
        }
        return demoReport;
    }

    private static InputStream resource(String path) {
        InputStream in = WebServer.class.getResourceAsStream(path);
        if (in == null) {
            throw new NotFound("Missing bundled resource " + path);
        }
        return in;
    }

    private TestSuite updateSuite(String id, TestSuite edited) {
        TestSuite existing = suites.load(id).orElseThrow(() -> new NotFound("Suite not found"));
        if (edited.cases().isEmpty()) {
            throw new IllegalArgumentException("A suite needs at least one test case");
        }
        if (edited.cases().size() > config.maxTests()) {
            throw new IllegalArgumentException("A suite can hold at most " + config.maxTests() + " tests");
        }
        for (TestCase c : edited.cases()) {
            if (c.input().isBlank() || c.input().length() > 4000) {
                throw new IllegalArgumentException("Test " + c.id() + " has an empty or overlong input");
            }
        }
        TestSuite saved = new TestSuite(existing.id(), edited.name(), existing.task(), edited.cases(),
                existing.createdAt(), existing.generation());
        suites.save(id, saved);
        return saved;
    }

    private static Map<String, Object> suiteSummary(TestSuite s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.id());
        m.put("name", s.name());
        m.put("task", s.task().name());
        m.put("cases", s.cases().size());
        m.put("createdAt", s.createdAt());
        m.put("generatorModel", s.generation() == null ? null : s.generation().generatorModel());
        return m;
    }

    private static void error(Context ctx, HttpStatus status, String message) {
        ctx.status(status).json(Map.of("error", message == null ? status.getMessage() : message));
    }

    static final class NotFound extends RuntimeException {
        NotFound(String message) {
            super(message);
        }
    }

    static final class Forbidden extends RuntimeException {
        Forbidden(String message) {
            super(message);
        }
    }
}
