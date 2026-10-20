package io.promptfuzzer.llm;

/**
 * Spaces requests evenly so a run never exceeds a requests-per-minute ceiling, regardless of concurrency.
 * Shared by all calls to one provider.
 */
public final class RateLimiter {

    private final long intervalNanos;
    private final Clock clock;
    private long nextSlot;

    public RateLimiter(int requestsPerMinute) {
        this(requestsPerMinute, Clock.SYSTEM);
    }

    RateLimiter(int requestsPerMinute, Clock clock) {
        if (requestsPerMinute <= 0) {
            throw new IllegalArgumentException("requestsPerMinute must be positive");
        }
        this.intervalNanos = 60_000_000_000L / requestsPerMinute;
        this.clock = clock;
        this.nextSlot = clock.nanoTime();
    }

    /** Blocks until the caller may send its request. */
    public void acquire() throws InterruptedException {
        long wait;
        synchronized (this) {
            long now = clock.nanoTime();
            long slot = Math.max(now, nextSlot);
            nextSlot = slot + intervalNanos;
            wait = slot - now;
        }
        if (wait > 0) {
            clock.sleepNanos(wait);
        }
    }

    /** Time source, replaceable in tests. */
    interface Clock {
        Clock SYSTEM = new Clock() {
            @Override
            public long nanoTime() {
                return System.nanoTime();
            }

            @Override
            public void sleepNanos(long nanos) throws InterruptedException {
                Thread.sleep(nanos / 1_000_000, (int) (nanos % 1_000_000));
            }
        };

        long nanoTime();

        void sleepNanos(long nanos) throws InterruptedException;
    }
}
