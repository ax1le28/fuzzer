package io.promptfuzzer.app;

import com.fasterxml.jackson.databind.JsonNode;
import io.promptfuzzer.analysis.ReportBuilder;
import io.promptfuzzer.analysis.RunReport;
import io.promptfuzzer.core.ExpectedBehavior;
import io.promptfuzzer.core.Json;
import io.promptfuzzer.core.TaskSpec;
import io.promptfuzzer.core.TestCase;
import io.promptfuzzer.core.TestCategory;
import io.promptfuzzer.core.TestSuite;
import io.promptfuzzer.execution.TestResult;
import io.promptfuzzer.execution.TestRunner;
import io.promptfuzzer.generation.RuleBasedMutator;
import io.promptfuzzer.llm.ChatModel;
import io.promptfuzzer.llm.ChatRequest;
import io.promptfuzzer.llm.ChatResponse;
import io.promptfuzzer.llm.Pricing;

import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Builds the bundled demo report. Responses come from a hand-written recording of two fictional models, but
 * everything downstream is the real pipeline: rule-based mutation, the runner, deterministic evaluation, and
 * failure analysis. The report is marked {@code demo} and the dashboard labels it as sample data.
 */
public final class DemoBuilder {

    static final String MESSAGE = "Sample data: hand-written responses from two fictional models, scored by the real "
            + "PromptFuzzer evaluator. Latency, token and cost figures are synthetic. Not a live run.";

    private DemoBuilder() {
    }

    public static RunReport build() {
        JsonNode recording = Json.read(resource("/demo/demo-recording.json"), JsonNode.class);
        TaskSpec task = Json.read(resource("/examples/" + recording.path("task").asText() + ".json"), TaskSpec.class);

        List<TestCase> cases = new ArrayList<>(new RuleBasedMutator(42).mutate(task));
        Map<String, Map<String, String[]>> outputs = new HashMap<>(); // model -> input -> [first, second]
        List<String> modelIds = new ArrayList<>();
        recording.path("models").fieldNames().forEachRemaining(modelIds::add);
        modelIds.forEach(m -> outputs.put(m, new HashMap<>()));

        for (JsonNode c : recording.path("cases")) {
            int seed = c.path("seedIndex").asInt();
            ExpectedBehavior behavior = ExpectedBehavior.valueOf(c.path("expectedBehavior").asText("MATCH_EXPECTED"));
            JsonNode expected = behavior != ExpectedBehavior.MATCH_EXPECTED ? null
                    : c.has("expectedOutput") ? c.get("expectedOutput") : task.examples().get(seed).expectedOutput();
            TestCase test = new TestCase(c.path("id").asText(), TestCategory.valueOf(c.path("category").asText()),
                    c.path("input").asText(), behavior, expected, c.path("rationale").asText(null),
                    TestCase.Origin.AI_GENERATED,
                    TestCase.ExpectationSource.valueOf(c.path("expectationSource").asText("SEED")), seed);
            cases.add(test);
            for (String model : modelIds) {
                String first = c.path("outputs").path(model).asText(null);
                String second = c.path("outputs2").path(model).asText(null);
                first = first != null ? first : defaultOutput(test);
                outputs.get(model).put(test.input(), new String[]{first, second != null ? second : first});
            }
        }
        for (TestCase rule : cases) {
            if (rule.origin() == TestCase.Origin.AI_GENERATED) {
                continue;
            }
            for (String model : modelIds) {
                String first = recording.path("ruleOverrides").path(model).path(rule.id()).asText(null);
                String second = recording.path("ruleOverrides2").path(model).path(rule.id()).asText(null);
                first = first != null ? first : defaultOutput(rule);
                outputs.get(model).put(rule.input(), new String[]{first, second != null ? second : first});
            }
        }

        TestSuite suite = new TestSuite("demo-suite", task.name() + " (sample)", task, cases,
                Instant.parse("2026-10-20T08:55:00Z"),
                new TestSuite.GenerationInfo("sample (hand-written)", 42, 0, 0, null, List.of()));

        List<ChatModel> models = new ArrayList<>();
        for (String id : modelIds) {
            JsonNode m = recording.path("models").path(id);
            models.add(new ReplayModel(id, new Pricing(m.path("inputPerMTok").asDouble(), m.path("outputPerMTok").asDouble()),
                    m.path("latencyMs").asLong(), m.path("thinkingTokens").asLong(), outputs.get(id)));
        }
        int repeats = recording.path("repeats").asInt(1);
        TestRunner.Options options = new TestRunner.Options(repeats, 1, 4096);
        List<TestResult> results = new TestRunner(models, null, options)
                .run(suite, new AtomicBoolean(), TestRunner.Listener.NONE);

        RunReport.RunSettings settings = new RunReport.RunSettings(modelIds, repeats, 1, 4096, null, 2.0, 1000);
        return new RunReport("demo", suite.id(), suite.name(), RunReport.Status.COMPLETED,
                Instant.parse("2026-10-20T09:00:00Z"), Instant.parse("2026-10-20T09:03:41Z"), settings,
                results.size(), results.size(), ReportBuilder.summarize(modelIds, results), results,
                ReportBuilder.analyze(results), null, suite, MESSAGE, true);
    }

    private static String defaultOutput(TestCase test) {
        return switch (test.expectedBehavior()) {
            case MATCH_EXPECTED -> Json.write(test.expectedOutput());
            case ASK_CLARIFICATION -> "{\"action\": \"clarify\", \"question\": \"Which room and setting do you mean?\"}";
            case REFUSE -> "{\"action\": \"reject\", \"reason\": \"That room, device or value is not supported.\"}";
        };
    }

    private static InputStream resource(String path) {
        InputStream in = DemoBuilder.class.getResourceAsStream(path);
        if (in == null) {
            throw new IllegalStateException("Missing bundled resource " + path);
        }
        return in;
    }

    /** Replays recorded outputs with synthetic but deterministic latency and token counts. */
    private static final class ReplayModel implements ChatModel {
        private final String id;
        private final Pricing pricing;
        private final long baseLatency;
        private final long thinkingTokens;
        private final Map<String, String[]> outputs;
        private final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();

        ReplayModel(String id, Pricing pricing, long baseLatency, long thinkingTokens, Map<String, String[]> outputs) {
            this.id = id;
            this.pricing = pricing;
            this.baseLatency = baseLatency;
            this.thinkingTokens = thinkingTokens;
            this.outputs = outputs;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public Optional<Pricing> pricing() {
            return Optional.of(pricing);
        }

        @Override
        public ChatResponse complete(ChatRequest request) {
            String[] recorded = outputs.get(request.user());
            if (recorded == null) {
                throw new IllegalStateException("No recorded output for: " + request.user());
            }
            int n = calls.computeIfAbsent(request.user(), k -> new AtomicInteger()).getAndIncrement();
            String text = recorded[Math.min(n, 1)];
            Random random = new Random((id + request.user() + n).hashCode());
            long latency = Math.round(baseLatency * (0.65 + 0.7 * random.nextDouble()) + text.length() * 2L);
            if (random.nextDouble() < 0.04) {
                latency *= 3; // occasional slow tail, as real APIs have
            }
            long in = Math.round((request.system().length() + request.user().length()) / 3.5);
            long out = Math.round(text.length() / 3.5) + thinkingTokens + random.nextInt(20);
            return new ChatResponse(text, in, out, latency, "end_turn");
        }
    }

    /** Lists recorded case ids, for tests. */
    static List<String> recordedIds() {
        JsonNode recording = Json.read(resource("/demo/demo-recording.json"), JsonNode.class);
        List<String> ids = new ArrayList<>();
        Iterator<JsonNode> it = recording.path("cases").elements();
        it.forEachRemaining(c -> ids.add(c.path("id").asText()));
        return ids;
    }
}
