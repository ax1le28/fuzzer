package io.promptfuzzer.analysis;

import io.promptfuzzer.core.TestCategory;
import io.promptfuzzer.evaluation.FailureType;

import java.util.Map;

/**
 * Aggregate reliability metrics for one target.
 *
 * @param passRate         passed / (passed + failed); errors are excluded because they say nothing about the model
 * @param passRateLow      lower bound of the 95% Wilson score interval for the pass rate
 * @param passRateHigh     upper bound of that interval
 * @param verifiedPasses   passes established purely by deterministic checks
 * @param judgedPasses     passes that relied on an AI judge for at least one check
 * @param heuristicPasses  passes that relied on a heuristic for at least one check
 * @param consistency      share of tests whose repeats all produced the same verdict and output (null when not repeated)
 * @param costUsd          estimated cost of target calls (null when pricing is unknown)
 */
public record ModelSummary(
        String model,
        int total,
        int passed,
        int failed,
        int errors,
        double passRate,
        double passRateLow,
        double passRateHigh,
        int verifiedPasses,
        int judgedPasses,
        int heuristicPasses,
        Map<TestCategory, CategoryStats> byCategory,
        Map<FailureType, Integer> byFailureType,
        Latency latency,
        long inputTokens,
        long outputTokens,
        Double costUsd,
        Double consistency) {

    public record CategoryStats(int total, int passed, int failed, int errors, double passRate) {
    }

    public record Latency(long mean, long p50, long p95, long max) {
    }
}
