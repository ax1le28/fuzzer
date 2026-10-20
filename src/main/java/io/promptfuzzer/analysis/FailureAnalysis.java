package io.promptfuzzer.analysis;

import io.promptfuzzer.evaluation.FailureType;
import io.promptfuzzer.execution.RunIssue;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Failures grouped by type, with rule-based suggestions and, optionally, AI-written insights. The two are kept
 * apart so readers always know which advice came from fixed rules and which from a model.
 *
 * <p>Model failures (graded {@code FAIL}) and run problems (call errors and skipped calls) are kept apart too:
 * a run where nothing could be graded has no clusters, and must not read as "every test passed".
 *
 * @param outcome    what the results as a whole show
 * @param clusters   one entry per failure type that occurred, most frequent first
 * @param issues     call errors and skipped calls by cause, most frequent first
 * @param aiInsights model-written analysis, or null when not requested or unavailable
 */
public record FailureAnalysis(Outcome outcome, List<Cluster> clusters, List<IssueGroup> issues, AiInsights aiInsights) {

    public FailureAnalysis {
        clusters = clusters == null ? List.of() : List.copyOf(clusters);
        issues = issues == null ? List.of() : List.copyOf(issues);
    }

    public enum Outcome {
        /** Something was graded, nothing failed, and there were no errors or skipped calls. */
        ALL_PASSED,
        /** Every graded result passed, but some calls errored or were skipped, so coverage is incomplete. */
        PASSED_WITH_ISSUES,
        /** At least one graded result failed. */
        FAILURES_FOUND,
        /** Nothing could be graded: no pass rate was measured. */
        NOTHING_GRADED
    }

    /**
     * @param issue        the cause
     * @param skipped      true when these calls were never sent
     * @param count        results with this cause
     * @param byModel      count per target
     * @param exampleIds   result ids of representative cases
     * @param firstMessage the first detailed error message, which carries the specific fix
     */
    public record IssueGroup(RunIssue issue, String label, String description, boolean skipped, int count,
                             Map<String, Integer> byModel, List<String> exampleIds, String firstMessage) {
    }

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
        return new FailureAnalysis(outcome, clusters, issues, insights);
    }
}
