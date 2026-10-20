package io.promptfuzzer.llm;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SafeguardsTest {

    private static final ChatRequest REQUEST = new ChatRequest("sys", "hi", 100);

    /** Fails with the given exceptions in order, then succeeds. */
    private static ChatModel flaky(AtomicInteger calls, LlmException... failures) {
        return new ChatModel() {
            @Override
            public String id() {
                return "flaky";
            }

            @Override
            public Optional<Pricing> pricing() {
                return Optional.of(new Pricing(1_000_000, 1_000_000)); // $1 per token: easy arithmetic
            }

            @Override
            public ChatResponse complete(ChatRequest request) {
                int n = calls.getAndIncrement();
                if (n < failures.length) {
                    throw failures[n];
                }
                return new ChatResponse("ok", 1, 1, 5, "end_turn");
            }
        };
    }

    @Test
    void retriesTransientErrorsHonouringRetryAfter() {
        AtomicInteger calls = new AtomicInteger();
        List<Long> sleeps = new ArrayList<>();
        ResilientChatModel model = new ResilientChatModel(
                flaky(calls, LlmException.forStatus(429, "slow down", Duration.ofSeconds(3)), LlmException.forStatus(529, "overloaded", null)),
                null, Budget.unlimited(), ResilientChatModel.RetryPolicy.DEFAULT, sleeps::add);
        assertEquals("ok", model.complete(REQUEST).text());
        assertEquals(3, calls.get());
        assertEquals(3000L, sleeps.get(0), "retry-after header wins");
        assertTrue(sleeps.get(1) >= 0 && sleeps.get(1) <= 2000, "exponential backoff with jitter: " + sleeps);
    }

    @Test
    void doesNotRetryClientErrors() {
        AtomicInteger calls = new AtomicInteger();
        ResilientChatModel model = new ResilientChatModel(flaky(calls, LlmException.forStatus(401, "bad key", null)),
                null, Budget.unlimited(), ResilientChatModel.RetryPolicy.DEFAULT, ms -> { });
        LlmException e = assertThrows(LlmException.class, () -> model.complete(REQUEST));
        assertEquals(401, e.status());
        assertEquals(1, calls.get());
    }

    @Test
    void givesUpAfterMaxAttempts() {
        AtomicInteger calls = new AtomicInteger();
        LlmException busy = LlmException.forStatus(503, "busy", null);
        ResilientChatModel model = new ResilientChatModel(flaky(calls, busy, busy, busy, busy, busy),
                null, Budget.unlimited(), new ResilientChatModel.RetryPolicy(3, Duration.ofMillis(1), Duration.ofMillis(2)), ms -> { });
        assertThrows(LlmException.class, () -> model.complete(REQUEST));
        assertEquals(3, calls.get());
    }

    @Test
    void budgetStopsCallsOnceCostLimitIsReached() {
        AtomicInteger calls = new AtomicInteger();
        Budget budget = new Budget(100, 3.5); // each call costs $2 with the test pricing
        ResilientChatModel model = new ResilientChatModel(flaky(calls), null, budget, ResilientChatModel.RetryPolicy.DEFAULT, ms -> { });
        model.complete(REQUEST);
        model.complete(REQUEST);
        assertThrows(Budget.BudgetExceededException.class, () -> model.complete(REQUEST));
        assertEquals(2, calls.get(), "the third call is never sent");
        assertEquals(4.0, budget.spentUsd(), 1e-9);
    }

    @Test
    void budgetCountsRequestsIncludingRetries() {
        AtomicInteger calls = new AtomicInteger();
        Budget budget = new Budget(2, 1000);
        ResilientChatModel model = new ResilientChatModel(
                flaky(calls, LlmException.forStatus(500, "x", null), LlmException.forStatus(500, "x", null)),
                null, budget, ResilientChatModel.RetryPolicy.DEFAULT, ms -> { });
        assertThrows(Budget.BudgetExceededException.class, () -> model.complete(REQUEST));
        assertEquals(2, calls.get());
    }

    @Test
    void rateLimiterSpacesRequests() throws InterruptedException {
        long[] now = {0};
        List<Long> waits = new ArrayList<>();
        RateLimiter limiter = new RateLimiter(60, new RateLimiter.Clock() {
            @Override
            public long nanoTime() {
                return now[0];
            }

            @Override
            public void sleepNanos(long nanos) {
                waits.add(nanos);
            }
        });
        limiter.acquire();
        limiter.acquire();
        limiter.acquire();
        assertEquals(List.of(1_000_000_000L, 2_000_000_000L), waits, "60/min = one per second");
    }

    @Test
    void pricingArithmetic() {
        assertEquals(0.000024, Pricing.forModel("claude-opus-5-5").orElseThrow().cost(1, 1), 1e-12);
        assertTrue(Pricing.forModel("unknown-model").isEmpty());
    }

    @Test
    void retryAfterParsing() {
        assertEquals(Duration.ofMillis(1500), LlmException.parseRetryAfter("1.5"));
        assertEquals(null, LlmException.parseRetryAfter("Wed, 21 Oct 2026 07:28:00 GMT"));
        assertEquals(null, LlmException.parseRetryAfter(null));
    }

    @Test
    void factoryRefusesNonCredentialEnvironmentVariables() {
        ModelFactory factory = ModelFactory.fromMap(Map.of("AWS_SECRET_ACCESS_KEY", "secret", "OPENAI_API_KEY", "k"));
        ModelConfig sneaky = new ModelConfig(ModelConfig.Provider.ENDPOINT, null, "https://evil.example", "AWS_SECRET_ACCESS_KEY",
                null, null, null, null, "x");
        assertThrows(IllegalArgumentException.class, () -> factory.create(sneaky));
        ModelConfig fine = new ModelConfig(ModelConfig.Provider.OPENAI_COMPATIBLE, "llama3", "http://localhost:11434/v1", null,
                null, null, null, null, null);
        assertEquals("llama3", factory.create(fine).id());
    }

    @Test
    void factoryExplainsMissingAnthropicKey() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ModelFactory.fromMap(Map.of()).create(ModelConfig.anthropic("claude-opus-5-5")));
        assertTrue(e.getMessage().contains("ANTHROPIC_API_KEY"));
    }

    @Test
    void openAiResponseParsing() {
        ChatResponse r = OpenAiCompatibleChatModel.parse("""
                {"choices": [{"message": {"content": "{\\"a\\": 1}"}, "finish_reason": "length"}],
                 "usage": {"prompt_tokens": 12, "completion_tokens": 34}}""", 50);
        assertEquals("{\"a\": 1}", r.text());
        assertTrue(r.truncated());
        assertEquals(12, r.inputTokens());
        assertEquals(34, r.outputTokens());
        assertThrows(LlmException.class, () -> OpenAiCompatibleChatModel.parse("<html>", 1));
    }

    @Test
    void endpointResponsePointer() {
        assertEquals("hello", HttpEndpointModel.extract("{\"output\": \"hello\"}", "/output"));
        assertEquals("{\"a\":1}", HttpEndpointModel.extract("{\"result\": {\"a\": 1}}", "/result"));
        assertEquals("raw", HttpEndpointModel.extract("raw", null));
        assertThrows(LlmException.class, () -> HttpEndpointModel.extract("{}", "/missing"));
    }
}
