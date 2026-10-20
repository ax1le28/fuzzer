package io.promptfuzzer.llm;

import java.time.Duration;
import java.util.Optional;

/** A failed model call, classified so callers can decide whether to retry. */
public class LlmException extends RuntimeException {

    private final int status;
    private final boolean retryable;
    private final Duration retryAfter;

    public LlmException(String message, int status, boolean retryable, Duration retryAfter, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.retryable = retryable;
        this.retryAfter = retryAfter;
    }

    public static LlmException fatal(String message) {
        return new LlmException(message, 0, false, null, null);
    }

    /** Classifies an HTTP status the same way for every provider. */
    public static LlmException forStatus(int status, String message, Duration retryAfter) {
        boolean retryable = status == 408 || status == 409 || status == 429 || status >= 500;
        return new LlmException("HTTP " + status + ": " + message, status, retryable, retryAfter, null);
    }

    /** HTTP status, or 0 for transport and client-side errors. */
    public int status() {
        return status;
    }

    public boolean retryable() {
        return retryable;
    }

    public Optional<Duration> retryAfter() {
        return Optional.ofNullable(retryAfter);
    }

    /** Parses a {@code retry-after} header value given in seconds. */
    public static Duration parseRetryAfter(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        try {
            double seconds = Double.parseDouble(header.trim());
            return seconds >= 0 ? Duration.ofMillis((long) (seconds * 1000)) : null;
        } catch (NumberFormatException e) {
            return null; // HTTP-date form: fall back to our own backoff
        }
    }
}
