package io.promptfuzzer.llm;

import io.promptfuzzer.MockLlmServer;
import io.promptfuzzer.app.AppConfig;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderPresetTest {

    @Test
    void parsesModelSpecs() {
        assertEquals(ModelConfig.anthropic("claude-haiku-5-5"), ProviderPreset.parseSpec("claude-haiku-5-5"));

        ModelConfig gemini = ProviderPreset.parseSpec("gemini:gemini-2.5-flash");
        assertEquals(ModelConfig.Provider.OPENAI_COMPATIBLE, gemini.provider());
        assertEquals("gemini-2.5-flash", gemini.model());
        assertEquals("https://generativelanguage.googleapis.com/v1beta/openai", gemini.baseUrl());
        assertEquals("GEMINI_API_KEY", gemini.apiKeyEnv());

        assertEquals("GROQ_API_KEY", ProviderPreset.parseSpec("groq:llama-3.3-70b-versatile").apiKeyEnv());

        ModelConfig ollama = ProviderPreset.parseSpec("ollama:qwen2.5:7b");
        assertEquals("qwen2.5:7b", ollama.model(), "Ollama tags contain a colon");
        assertEquals("http://localhost:11434/v1", ollama.baseUrl());
        assertEquals(ProviderPreset.NO_KEY, ollama.apiKeyEnv());

        ModelConfig custom = ProviderPreset.parseSpec("openai:mistral-small@http://10.0.0.5:8000/v1#none");
        assertEquals("mistral-small", custom.model());
        assertEquals("http://10.0.0.5:8000/v1", custom.baseUrl());
        assertEquals("none", custom.apiKeyEnv());
        assertEquals("OPENAI_API_KEY", ProviderPreset.parseSpec("openai:gpt-x").apiKeyEnv());
    }

    @Test
    void freeAndLocalProvidersNeedNoAnthropicKey() {
        ModelFactory noKeys = ModelFactory.fromMap(Map.of());
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> noKeys.create(ProviderPreset.parseSpec("gemini:gemini-2.5-flash")));
        assertTrue(e.getMessage().contains("GEMINI_API_KEY"), e.getMessage());

        ChatModel ollama = noKeys.create(ProviderPreset.parseSpec("ollama:qwen2.5:7b"));
        assertEquals(0.0, ollama.pricing().orElseThrow().cost(1_000_000, 1_000_000), "local models cost nothing");
        assertTrue(noKeys.available(ProviderPreset.OLLAMA));

        ModelFactory gemini = ModelFactory.fromMap(Map.of("GEMINI_API_KEY", "g"));
        assertTrue(gemini.available(ProviderPreset.GEMINI));
        assertTrue(gemini.create(ProviderPreset.parseSpec("gemini:gemini-2.5-flash")).pricing().isEmpty(),
                "free-tier prices are unknown rather than guessed");
    }

    @Test
    void defaultModelFollowsTheAvailableKey() {
        assertEquals("claude-opus-5-5", AppConfig.fromEnv(Map.<String, String>of()::get).defaultModel());
        assertEquals("gemini:gemini-2.5-flash", AppConfig.fromEnv(Map.of("GEMINI_API_KEY", "g")::get).defaultModel());
        assertEquals("groq:llama-3.3-70b-versatile", AppConfig.fromEnv(Map.of("GROQ_API_KEY", "g")::get).defaultModel());
        assertEquals("ollama:llama3.1:8b",
                AppConfig.fromEnv(Map.of("GROQ_API_KEY", "g", "PF_DEFAULT_MODEL", "ollama:llama3.1:8b")::get).defaultModel());
    }

    @Test
    void sendsTheRightKeyOrNone() throws Exception {
        try (MockLlmServer server = new MockLlmServer((system, user) -> "{\"ok\": true}", 0)) {
            ModelFactory factory = ModelFactory.fromMap(Map.of("GROQ_API_KEY", "groq-secret", "OPENAI_API_KEY", "openai-secret"));
            ChatRequest request = new ChatRequest("sys", "hi", 50);

            factory.create(ProviderPreset.GROQ.config("llama", server.baseUrl())).complete(request);
            factory.create(ProviderPreset.OLLAMA.config("qwen", server.baseUrl())).complete(request);

            assertEquals("Bearer groq-secret", server.authorizations().get(0));
            assertEquals("null", server.authorizations().get(1), "no key, and in particular not OPENAI_API_KEY, goes to a keyless server");
        }
    }

    @Test
    void presetsLimitTheRequestRateForFreeTiers() {
        assertEquals(10, ProviderPreset.forBaseUrl("https://generativelanguage.googleapis.com/v1beta/openai/").orElseThrow().requestsPerMinute());
        assertNull(ProviderPreset.forBaseUrl("http://example.com/v1").orElse(null));
        assertTrue(ProviderPreset.isLocalUrl("http://localhost:11434/v1"));
        assertTrue(!ProviderPreset.isLocalUrl("https://api.groq.com/openai/v1"));
    }
}
