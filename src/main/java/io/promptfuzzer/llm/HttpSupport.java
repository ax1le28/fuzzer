package io.promptfuzzer.llm;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;

/** Minimal JSON-over-HTTP plumbing shared by the non-SDK providers. */
final class HttpSupport {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private HttpSupport() {
    }

    record Result(String body, long latencyMs) {
    }

    static Result postJson(String url, String json, Map<String, String> headers, Duration timeout) {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));
        headers.forEach(req::header);
        long start = System.nanoTime();
        try {
            HttpResponse<String> response = CLIENT.send(req.build(), HttpResponse.BodyHandlers.ofString());
            long latency = (System.nanoTime() - start) / 1_000_000;
            int status = response.statusCode();
            if (status / 100 != 2) {
                String retryAfter = response.headers().firstValue("retry-after").orElse(null);
                throw LlmException.forStatus(status, abbreviate(response.body()), LlmException.parseRetryAfter(retryAfter));
            }
            return new Result(response.body(), latency);
        } catch (HttpTimeoutException e) {
            throw new LlmException("Request to " + url + " timed out", 408, true, null, e);
        } catch (IOException e) {
            throw new LlmException("Network error calling " + url + ": " + e.getMessage(), 0, true, null, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("Interrupted", 0, false, null, e);
        }
    }

    static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }
}
