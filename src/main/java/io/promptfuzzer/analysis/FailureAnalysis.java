package io.promptfuzzer.analysis;

import io.promptfuzzer.evaluation.FailureType;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Failures grouped by type, with rule-based suggestions and, optionally, AI-written insights. The two are kept
 * apart so readers always know which advice came from fixed rules and which from a model.
 *
 * @param clusters   one entry per failure type that occurred, most frequent first
 * @param aiInsights model-written analysis, or null when not requested or unavailable
 */
public record FailureAnalysis(List<Cluster> clusters, AiInsights aiInsights) {

    /**
     * @param share        fraction of all failures across all targets
     * @param byModel      failure count per target
     * @param categories   test categories in which this failure occurred, with counts
     * @param exampleIds   result ids of representative failures
     * @param suggestion   fixed, rule-based advice for this failure type
     */
    public record Cluster(
            FailureType type,
            String label,
            String description,
            int count,
            double share,
            Map<String, Integer> byModel,
            Map<String, Integer> categories,
            List<String> exampleIds,
            String suggestion) {
    }

    /**
     * @param model            the model that wrote the analysis
     * @param summary          a short diagnosis
     * @param recommendations  concrete changes to the application's instructions
     * @param promptAdditions  suggested text to add to the system prompt
     */
    public record AiInsights(String model, Instant generatedAt, String summary,
                             List<Recommendation> recommendations, String promptAdditions, String error) {
    }

    public record Recommendation(String title, String detail, List<String> addresses) {
    }

    public FailureAnalysis withAiInsights(AiInsights insights) {
        return new FailureAnalysis(clusters, insights);
    }
}
