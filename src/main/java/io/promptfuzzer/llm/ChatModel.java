package io.promptfuzzer.llm;

import java.util.Optional;

/**
 * A text-in, text-out model or AI application endpoint. Implementations must be thread-safe: the runner calls
 * them concurrently.
 */
public interface ChatModel {

    /** Identifier shown in reports, e.g. {@code claude-opus-5-5} or {@code endpoint:my-app}. */
    String id();

    /** Price used for cost estimates, when known. */
    Optional<Pricing> pricing();

    /**
     * Sends one request. Throws {@link LlmException} for provider errors; {@link LlmException#retryable()}
     * tells callers whether trying again can help.
     */
    ChatResponse complete(ChatRequest request);
}
