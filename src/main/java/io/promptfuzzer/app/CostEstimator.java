package io.promptfuzzer.app;

import io.promptfuzzer.core.TestCase;
import io.promptfuzzer.core.TestSuite;
import io.promptfuzzer.llm.Pricing;

import java.util.List;
import java.util.Optional;

/**
 * Rough pre-flight cost estimates, shown before a run starts. Token counts are approximated from character
 * counts, so treat the result as an order of magnitude; the budget enforces the real ceiling during the run.
 */
public final class CostEstimator {

    /** Output tokens a typical structured answer uses, including some reasoning. */
    static final int TYPICAL_OUTPUT_TOKENS = 450;
    static final int JUDGE_INPUT_TOKENS = 700;
    static final int JUDGE_OUTPUT_TOKENS = 250;

    private CostEstimator() {
    }

    /**
     * @param calls       target calls planned
     * @param judgeCalls  upper bound on judge calls
     * @param typicalUsd  expected cost if outputs are typical length (null when any price is unknown)
     * @param worstUsd    cost if every call used its full output budget (null when any price is unknown)
     * @param unpriced    targets with no known price
     */
    public record Estimate(int calls, int judgeCalls, long inputTokens, Double typicalUsd, Double worstUsd,
                           List<String> unpriced) {
    }

    public record Target(String id, Optional<Pricing> pricing) {
    }

    public static Estimate estimate(TestSuite suite, List<Target> targets, int repeats, int maxOutputTokens,
                                    Target judge) {
        long systemTokens = tokens(suite.task().systemPrompt());
        long perPassInput = 0;
        for (TestCase c : suite.cases()) {
            perPassInput += systemTokens + tokens(c.input()) + 10;
        }
        int n = suite.cases().size() * repeats;
        int judgeable = judge == null ? 0 : (int) suite.cases().stream().filter(c -> needsJudge(suite, c)).count() * repeats;

        double typical = 0;
        double worst = 0;
        boolean allPriced = true;
        List<String> unpriced = targets.stream().filter(t -> t.pricing().isEmpty()).map(Target::id).toList();
        for (Target t : targets) {
            if (t.pricing().isEmpty()) {
                allPriced = false;
                continue;
            }
            Pricing p = t.pricing().get();
            typical += p.cost(perPassInput * repeats, (long) n * TYPICAL_OUTPUT_TOKENS);
            worst += p.cost(perPassInput * repeats, (long) n * maxOutputTokens);
        }
        int judgeCalls = judgeable * targets.size();
        if (judge != null && judgeCalls > 0) {
            if (judge.pricing().isPresent()) {
                double j = judge.pricing().get().cost((long) judgeCalls * JUDGE_INPUT_TOKENS, (long) judgeCalls * JUDGE_OUTPUT_TOKENS);
                typical += j;
                worst += judge.pricing().get().cost((long) judgeCalls * JUDGE_INPUT_TOKENS, (long) judgeCalls * 2048);
            } else {
                allPriced = false;
            }
        }
        return new Estimate(n * targets.size(), judgeCalls, perPassInput * repeats * targets.size(),
                allPriced ? typical : null, allPriced ? worst : null, unpriced);
    }

    /** True when evaluating this case may need a judge call (an upper bound, not a certainty). */
    static boolean needsJudge(TestSuite suite, TestCase c) {
        return switch (c.expectedBehavior()) {
            case MATCH_EXPECTED -> suite.task().semanticFields().stream()
                    .anyMatch(f -> c.expectedOutput().hasNonNull(f));
            case ASK_CLARIFICATION -> suite.task().clarificationOutput() == null;
            case REFUSE -> suite.task().refusalOutput() == null;
        };
    }

    /** About 3.5 characters per token for English prose and JSON. */
    static long tokens(String text) {
        return text == null ? 0 : Math.round(text.length() / 3.5) + 1;
    }
}
