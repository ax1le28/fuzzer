package io.promptfuzzer.analysis;

import io.promptfuzzer.core.TestSuite;
import io.promptfuzzer.execution.TestResult;

import java.time.Instant;
import java.util.List;

/**
 * Everything about one run: configuration, per-target summaries, every individual result, and the failure
 * analysis. Saved as JSON so a report can be reopened, shared, or diffed later.
 *
 * @param demo true for the bundled sample report; the dashboard labels it prominently
 */
public record RunReport(
        String id,
        String suiteId,
        String suiteName,
        Status status,
        Instant startedAt,
        Instant finishedAt,
        RunSettings settings,
        int completed,
        int total,
        List<ModelSummary> models,
        List<TestResult> results,
        FailureAnalysis analysis,
        JudgeUsage judge,
        TestSuite suite,
        String message,
        boolean demo) {

    /**
     * How the run itself ended, independent of how the model did. {@code COMPLETED_WITH_ERRORS} means it finished
     * but some calls errored or were skipped; the analysis outcome says whether anything was graded at all.
     * {@code FAILED} means the run crashed.
     */
    public enum Status { RUNNING, COMPLETED, COMPLETED_WITH_ERRORS, CANCELLED, FAILED }

    /**
     * @param targets       target ids in the order they were run
     * @param maxCostUsd    budget ceiling that applied
     * @param maxRequests   request ceiling that applied
     */
    public record RunSettings(List<String> targets, int repeats, int concurrency, int maxOutputTokens,
                              String judgeModel, double maxCostUsd, int maxRequests) {
    }

    public record JudgeUsage(String model, long calls, long inputTokens, long outputTokens, Double costUsd) {
    }
}
