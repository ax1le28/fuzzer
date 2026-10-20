package io.promptfuzzer.analysis;

import io.promptfuzzer.core.TestCategory;
import io.promptfuzzer.evaluation.FailureType;
import io.promptfuzzer.execution.RunIssue;

import java.util.Map;

/**
 * Aggregate reliability metrics for one target.
 *
 * @param errors           calls that were attempted but produced nothing gradable (see {@code issues})
 * @param skipped          calls that were never sent: a limit was reached, the endpoint was down, or the run was cancelled
 * @param passRate         passed / (passed + failed); errors and skipped calls are excluded because they say nothing
 *                         about the model. Null when nothing was graded: a pass rate was not measured, which is
 *                         different from 0%
 * @param passRateLow      lower bound of the 95% Wilson score interval for the pass rate (null when not measured)
 * @param passRateHigh     upper bound of that interval (null when not measured)
 * @param verifiedPasses   passes established purely by deterministic checks
 * @param judgedPasses     passes that relied on an AI judge for at least one check
 * @param heuristicPasses  passes that relied on a heuristic for at least one check
 * @param consistency      share of tests whose repeats all produced the same verdict and output (null when not repeated)
 * @param costUsd          estimated cost of target calls (null when pricing is unknown)
 * @param issues           errors and skipped calls by cause
 */
public record ModelSummary(
        String model,
        int total,
        int passed,
        int failed,
        int errors,
        int skipped,
        Double passRate,
        Double passRateLow,
        Double passRateHigh,
        int verifiedPasses,
        int judgedPasses,
        int heuristicPasses,
        Map<TestCategory, CategoryStats> byCategory,
        Map<FailureType, Integer> byFailureType,
        Latency latency,
        long inputTokens,
        long outputTokens,
        Double costUsd,
        Double consistency,
        Map<RunIssue, Integer> issues) {

    /** Results whose verdict counts towards the pass rate. */
    public int graded() {
        return passed + failed;
    }

    /** @param passRate null when no result in the category was graded */
    public record CategoryStats(int total, int passed, int failed, int errors, int skipped, Double passRate) {
    }

    public record Latency(long mean, long p50, long p95, long max) {
    }
}
