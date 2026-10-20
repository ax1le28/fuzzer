package io.promptfuzzer.llm;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Decorates a model with the safeguards every call needs: a budget check, client-side rate limiting, and
 * retries with exponential backoff that honour the provider's {@code retry-after} hint.
 *
 * <p>Only transient failures (429, 5xx, timeouts, dropped connections) are retried. Authentication errors,
 * bad requests and servers that cannot be reached at all fail immediately, and an exhausted budget is never
 * retried. Every attempt, including a retry, is charged to the budget as one request.
 *
 * <p>After {@value #CONNECTION_FAILURE_LIMIT} consecutive connection failures the endpoint is treated as down:
 * further calls throw {@link EndpointUnavailableException} without touching the network or the budget, so a
 * stopped local server ends a run in seconds instead of failing every remaining test one by one.
 */
public final class ResilientChatModel implements ChatModel {

    private final ChatModel delegate;
    private final RateLimiter rateLimiter;
    private final Budget budget;
    private final RetryPolicy retry;
    private final Sleeper sleeper;
    private final AtomicInteger consecutiveConnectionFailures = new AtomicInteger();
    private volatile LlmException.ConnectionFailedException lastConnectionFailure;

    /** Consecutive connection failures after which the endpoint is no longer called. */
    public static final int CONNECTION_FAILURE_LIMIT = 3;

    public ResilientChatModel(ChatModel delegate, RateLimiter rateLimiter, Budget budget, RetryPolicy retry) {
        this(delegate, rateLimiter, budget, retry, Thread::sleep);
    }

    ResilientChatModel(ChatModel delegate, RateLimiter rateLimiter, Budget budget, RetryPolicy retry, Sleeper sleeper) {
        this.delegate = delegate;
        this.rateLimiter = rateLimiter;
        this.budget = budget;
        this.retry = retry;
        this.sleeper = sleeper;
    }

    @Override
    public String id() {
        return delegate.id();
    }

    @Override
    public Optional<Pricing> pricing() {
        return delegate.pricing();
    }

    @Override
    public ChatResponse complete(ChatRequest request) {
        for (int attempt = 1; ; attempt++) {
            LlmException.ConnectionFailedException down = lastConnectionFailure;
            if (down != null && consecutiveConnectionFailures.get() >= CONNECTION_FAILURE_LIMIT) {
                throw new EndpointUnavailableException(down);
            }
            budget.acquire();
            try {
                if (rateLimiter != null) {
                    rateLimiter.acquire();
                }
                ChatResponse response = delegate.complete(request);
                consecutiveConnectionFailures.set(0);
                pricing().ifPresent(p -> budget.record(p.cost(response.inputTokens(), response.outputTokens())));
                return response;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LlmException("Interrupted", 0, false, null, e);
            } catch (LlmException.ConnectionFailedException e) {
                lastConnectionFailure = e;
                consecutiveConnectionFailures.incrementAndGet();
                throw e;
            } catch (LlmException e) {
                if (!e.retryable() || attempt >= retry.maxAttempts()) {
                    throw e;
                }
                try {
                    sleeper.sleep(retry.delayFor(attempt, e.retryAfter().orElse(null)).toMillis());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    /**
     * @param maxAttempts total attempts including the first
     * @param baseDelay   delay before the first retry; doubles each attempt
     * @param maxDelay    cap on any single delay
     */
    public record RetryPolicy(int maxAttempts, Duration baseDelay, Duration maxDelay) {

        public static final RetryPolicy DEFAULT = new RetryPolicy(4, Duration.ofSeconds(1), Duration.ofSeconds(30));

        public RetryPolicy {
            if (maxAttempts < 1) {
                throw new IllegalArgumentException("maxAttempts must be at least 1");
            }
        }

        /** Server hint if given (capped), otherwise exponential backoff with full jitter. */
        Duration delayFor(int attempt, Duration retryAfter) {
            if (retryAfter != null) {
                return retryAfter.compareTo(maxDelay) > 0 ? maxDelay : retryAfter;
            }
            long exp = baseDelay.toMillis() * (1L << Math.min(attempt - 1, 20));
            long capped = Math.min(exp, maxDelay.toMillis());
            return Duration.ofMillis(capped / 2 + ThreadLocalRandom.current().nextLong(capped / 2 + 1));
        }
    }

    /** Thrown instead of calling an endpoint that has repeatedly refused connections during this operation. */
    public static final class EndpointUnavailableException extends LlmException {
        private final String endpoint;

        EndpointUnavailableException(LlmException.ConnectionFailedException last) {
            super("Not sent: stopped calling " + last.endpoint() + " after " + CONNECTION_FAILURE_LIMIT
                    + " consecutive connection failures. Last error: " + last.getMessage(), 0, false, null, last);
            this.endpoint = last.endpoint();
        }

        public String endpoint() {
            return endpoint;
        }
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }
}
