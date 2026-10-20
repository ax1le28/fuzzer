package io.promptfuzzer.llm;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A hard ceiling on API usage for one operation (a generation or a run). Every call must pass
 * {@link #acquire()} first; once either limit is reached, further calls fail fast with
 * {@link BudgetExceededException} instead of spending money.
 *
 * <p>Calls already in flight when the limit is crossed still complete, so the overshoot is bounded by the
 * runner's concurrency times the cost of one call.
 */
public final class Budget {

    private final int maxRequests;
    private final double maxCostUsd;
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicLong costMicros = new AtomicLong();

    public Budget(int maxRequests, double maxCostUsd) {
        if (maxRequests <= 0 || maxCostUsd <= 0) {
            throw new IllegalArgumentException("Budget limits must be positive");
        }
        this.maxRequests = maxRequests;
        this.maxCostUsd = maxCostUsd;
    }

    public static Budget unlimited() {
        return new Budget(Integer.MAX_VALUE, Double.MAX_VALUE);
    }

    /** Reserves one request. */
    public void acquire() {
        if (spentUsd() >= maxCostUsd) {
            throw new BudgetExceededException(String.format(
                    "Cost limit of $%.2f reached (spent $%.4f)", maxCostUsd, spentUsd()));
        }
        int n = requests.incrementAndGet();
        if (n > maxRequests) {
            requests.decrementAndGet();
            throw new BudgetExceededException("Request limit of " + maxRequests + " reached");
        }
    }

    /** Records the actual cost of a finished call. */
    public void record(double costUsd) {
        if (costUsd > 0) {
            costMicros.addAndGet(Math.round(costUsd * 1_000_000));
        }
    }

    public int requestsUsed() {
        return requests.get();
    }

    public double spentUsd() {
        return costMicros.get() / 1_000_000.0;
    }

    public int maxRequests() {
        return maxRequests;
    }

    public double maxCostUsd() {
        return maxCostUsd;
    }

    public static final class BudgetExceededException extends LlmException {
        public BudgetExceededException(String message) {
            super(message, 0, false, null, null);
        }
    }
}
