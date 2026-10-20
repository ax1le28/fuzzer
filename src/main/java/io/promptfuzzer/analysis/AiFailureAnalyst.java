package io.promptfuzzer.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import io.promptfuzzer.core.DataFence;
import io.promptfuzzer.core.Json;
import io.promptfuzzer.core.TaskSpec;
import io.promptfuzzer.evaluation.JsonExtractor;
import io.promptfuzzer.execution.TestResult;
import io.promptfuzzer.llm.ChatModel;
import io.promptfuzzer.llm.ChatRequest;
import io.promptfuzzer.llm.ChatResponse;
import io.promptfuzzer.llm.LlmException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Asks a model to explain the failure clusters and propose concrete changes to the application's system
 * prompt. The output is advisory: it is stored and displayed separately from the deterministic analysis and
 * labelled as AI-generated.
 */
public final class AiFailureAnalyst {

    private static final int MAX_EXAMPLES = 14;

    private final ChatModel model;

    public AiFailureAnalyst(ChatModel model) {
        this.model = model;
    }

    public FailureAnalysis.AiInsights analyze(TaskSpec task, FailureAnalysis analysis, List<TestResult> results) {
        if (analysis.clusters().isEmpty()) {
            return null;
        }
        DataFence fence = DataFence.create();
        Map<String, TestResult> byId = ReportBuilder.byId(results);
        StringBuilder clusters = new StringBuilder();
        int examples = 0;
        for (FailureAnalysis.Cluster c : analysis.clusters()) {
            clusters.append("### ").append(c.type()).append(" (").append(c.label()).append("): ")
                    .append(c.count()).append(" failures, categories ").append(c.categories()).append("\n");
            for (String id : c.exampleIds()) {
                if (examples >= MAX_EXAMPLES) {
                    break;
                }
                TestResult r = byId.get(id);
                if (r == null) {
                    continue;
                }
                examples++;
                clusters.append(fence.wrap("failed-input", r.input())).append("\n")
                        .append("Expected: ").append(r.expectedOutput() == null ? r.expectedBehavior() : Json.write(r.expectedOutput())).append("\n")
                        .append(fence.wrap("actual-output", abbreviate(r.rawOutput()))).append("\n")
                        .append("Why it failed: ").append(r.evaluation().summary()).append("\n\n");
            }
        }

        String system = """
                You are a senior AI engineer reviewing the results of an automated reliability test of an LLM
                application. Diagnose why the failures happen and propose specific, minimal changes to the
                application's system prompt. Base every claim on the evidence given; say so when evidence is thin.
                %s
                Reply with only a JSON object:
                {"summary": "<2-3 sentences>",
                 "recommendations": [{"title": "<short>", "detail": "<what to change and why>", "addresses": ["<FAILURE_TYPE>", ...]}],
                 "prompt_additions": "<text to append to the system prompt>"}
                """.formatted(fence.instruction());
        String user = """
                Application: %s — %s

                Current system prompt:
                %s

                Failure clusters with representative examples:
                %s
                """.formatted(task.name(), task.description(), fence.wrap("system-prompt", task.systemPrompt()), clusters);

        try {
            ChatResponse response = model.complete(new ChatRequest(system, user, 8000));
            return parse(model.id(), response.text());
        } catch (LlmException | IllegalArgumentException e) {
            return new FailureAnalysis.AiInsights(model.id(), Instant.now(), null, List.of(), null,
                    "AI analysis unavailable: " + e.getMessage());
        }
    }

    static FailureAnalysis.AiInsights parse(String modelId, String text) {
        JsonExtractor.Extraction extraction = JsonExtractor.extract(text);
        if (!extraction.found()) {
            throw new IllegalArgumentException("analysis reply was not JSON");
        }
        JsonNode root = extraction.value();
        List<FailureAnalysis.Recommendation> recs = new ArrayList<>();
        for (JsonNode r : root.path("recommendations")) {
            List<String> addresses = new ArrayList<>();
            r.path("addresses").forEach(a -> addresses.add(a.asText()));
            recs.add(new FailureAnalysis.Recommendation(r.path("title").asText(""), r.path("detail").asText(""), addresses));
        }
        return new FailureAnalysis.AiInsights(modelId, Instant.now(), root.path("summary").asText(null), recs,
                root.path("prompt_additions").asText(null), null);
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 600 ? s.substring(0, 600) + "…" : s;
    }
}
