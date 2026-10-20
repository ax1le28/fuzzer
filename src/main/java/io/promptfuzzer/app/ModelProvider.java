package io.promptfuzzer.app;

import io.promptfuzzer.llm.Budget;
import io.promptfuzzer.llm.ChatModel;
import io.promptfuzzer.llm.ModelConfig;
import io.promptfuzzer.llm.ModelFactory;
import io.promptfuzzer.llm.ProviderPreset;
import io.promptfuzzer.llm.RateLimiter;
import io.promptfuzzer.llm.ResilientChatModel;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Creates models wrapped in the standard safeguards. Rate limiters are shared per provider endpoint across
 * concurrent runs, so two runs together still respect the per-minute ceiling.
 */
public final class ModelProvider {

    private final ModelFactory factory;
    private final int requestsPerMinute;
    private final Map<String, RateLimiter> limiters = new ConcurrentHashMap<>();

    public ModelProvider(ModelFactory factory, int requestsPerMinute) {
        this.factory = factory;
        this.requestsPerMinute = requestsPerMinute;
    }

    public ModelFactory factory() {
        return factory;
    }

    /** A safeguarded model charging to {@code budget}. */
    public ChatModel create(ModelConfig config, Budget budget) {
        ChatModel raw = factory.create(config);
        String key = config.provider() + "|" + (config.baseUrl() == null ? "" : config.baseUrl());
        int rpm = ProviderPreset.forBaseUrl(config.baseUrl()).map(ProviderPreset::requestsPerMinute)
                .filter(r -> r > 0).map(r -> Math.min(r, requestsPerMinute)).orElse(requestsPerMinute);
        RateLimiter limiter = limiters.computeIfAbsent(key, k -> new RateLimiter(rpm));
        return new ResilientChatModel(raw, limiter, budget, ResilientChatModel.RetryPolicy.DEFAULT);
    }

    /** The unwrapped model, used only to read its id and pricing. */
    public ChatModel describe(ModelConfig config) {
        return factory.create(config);
    }
}
