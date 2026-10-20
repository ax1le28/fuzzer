package io.promptfuzzer.llm;

import java.net.URI;
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

    /**
     * The server could not be reached at all (refused, unknown host, connect timeout). Not retried: the
     * condition rarely clears within a backoff window, and {@link ResilientChatModel} stops calling the endpoint
     * after a few of these in a row.
     */
    public static ConnectionFailedException connectionFailed(String url, Throwable cause) {
        String endpoint = origin(url);
        return new ConnectionFailedException("Cannot connect to " + endpoint + " (" + describe(cause) + "). "
                + connectionHint(url), endpoint, cause);
    }

    /** What to do when {@code url} cannot be reached, phrased for the dashboard and the CLI. */
    public static String connectionHint(String url) {
        if (ProviderPreset.isLocalUrl(url)) {
            return isOllama(url)
                    ? "If Ollama is not running, start it with `ollama serve` (or open the Ollama app), then try again."
                    : "Check that the local server is running and listening on that port, then try again.";
        }
        return "Check the URL, your network connection and any proxy or firewall, then try again.";
    }

    static boolean isOllama(String url) {
        if (ProviderPreset.forBaseUrl(url).filter(p -> p == ProviderPreset.OLLAMA).isPresent()) {
            return true;
        }
        try {
            return URI.create(url.trim()).getPort() == 11434;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** {@code scheme://host:port} of a URL, used to name an endpoint without its path. */
    static String origin(String url) {
        try {
            URI uri = URI.create(url.trim());
            if (uri.getScheme() != null && uri.getHost() != null) {
                return uri.getScheme() + "://" + uri.getHost() + (uri.getPort() >= 0 ? ":" + uri.getPort() : "");
            }
        } catch (IllegalArgumentException e) {
            // fall through
        }
        return url;
    }

    /**
     * A readable one-line description of an exception and its causes. Java's networking exceptions often have
     * no message ({@code ConnectException} for a refused connection), so the class name is always included.
     */
    public static String describe(Throwable t) {
        if (t == null) {
            return "unknown error";
        }
        StringBuilder out = new StringBuilder(t.getClass().getSimpleName());
        String message = null;
        for (Throwable c = t; c != null && message == null; c = c.getCause() == c ? null : c.getCause()) {
            if (c.getMessage() != null && !c.getMessage().isBlank()) {
                message = c.getMessage();
                if (c != t) {
                    out.append(" caused by ").append(c.getClass().getSimpleName());
                }
            }
        }
        if (message != null) {
            out.append(": ").append(message);
        }
        return out.toString();
    }

    /** See {@link #connectionFailed(String, Throwable)}. */
    public static final class ConnectionFailedException extends LlmException {
        private final String endpoint;

        ConnectionFailedException(String message, String endpoint, Throwable cause) {
            super(message, 0, false, null, cause);
            this.endpoint = endpoint;
        }

        /** {@code scheme://host:port} of the unreachable server. */
        public String endpoint() {
            return endpoint;
        }
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
