package io.promptfuzzer.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;

import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Claude via the official Anthropic Java SDK.
 *
 * <p>SDK-level retries are disabled so that {@link ResilientChatModel} applies one retry and budget policy to
 * every provider. No server-side model fallback is configured on purpose: a fuzzer must measure the model the
 * user selected, so a refusal is recorded as the outcome rather than silently re-routed to another model.
 */
public final class AnthropicChatModel implements ChatModel {

    private final AnthropicClient client;
    private final String model;
    private final String effort;
    private final Pricing pricing;

    public AnthropicChatModel(String apiKey, String baseUrl, String model, String effort, Pricing pricing) {
        AnthropicOkHttpClient.Builder builder = AnthropicOkHttpClient.builder()
                .apiKey(apiKey)
                .maxRetries(0)
                .timeout(Duration.ofMinutes(5));
        if (baseUrl != null && !baseUrl.isBlank()) {
            builder.baseUrl(baseUrl);
        }
        this.client = builder.build();
        this.model = model;
        this.effort = effort;
        this.pricing = pricing != null ? pricing : Pricing.forModel(model).orElse(null);
    }

    @Override
    public String id() {
        return model;
    }

    @Override
    public Optional<Pricing> pricing() {
        return Optional.ofNullable(pricing);
    }

    @Override
    public ChatResponse complete(ChatRequest request) {
        MessageCreateParams.Builder params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(request.maxTokens())
                .addUserMessage(request.user());
        if (request.system() != null && !request.system().isBlank()) {
            params.system(request.system());
        }
        if (effort != null && !effort.isBlank()) {
            params.outputConfig(OutputConfig.builder()
                    .effort(OutputConfig.Effort.of(effort.toLowerCase(Locale.ROOT)))
                    .build());
        }

        long start = System.nanoTime();
        try {
            Message message = client.messages().create(params.build());
            long latency = (System.nanoTime() - start) / 1_000_000;
            String text = message.content().stream()
                    .flatMap(block -> block.text().stream())
                    .map(t -> t.text())
                    .collect(Collectors.joining());
            String stop = message.stopReason().map(s -> s.toString().toLowerCase(Locale.ROOT)).orElse(null);
            return new ChatResponse(text, message.usage().inputTokens(), message.usage().outputTokens(), latency, stop);
        } catch (AnthropicServiceException e) {
            Duration retryAfter = e.headers().values("retry-after").stream().findFirst()
                    .map(LlmException::parseRetryAfter).orElse(null);
            throw new LlmException("Anthropic API error " + e.statusCode() + ": " + e.getMessage(),
                    e.statusCode(), isRetryable(e.statusCode()), retryAfter, e);
        } catch (AnthropicIoException e) {
            throw new LlmException("Network error calling Anthropic: " + e.getMessage(), 0, true, null, e);
        }
    }

    private static boolean isRetryable(int status) {
        // 529 is Anthropic's "overloaded" status.
        return status == 408 || status == 409 || status == 429 || status >= 500;
    }
}
