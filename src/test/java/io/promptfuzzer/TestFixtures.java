package io.promptfuzzer;

import com.fasterxml.jackson.databind.JsonNode;
import io.promptfuzzer.core.ExpectedBehavior;
import io.promptfuzzer.core.Json;
import io.promptfuzzer.core.TaskSpec;
import io.promptfuzzer.core.TestCase;
import io.promptfuzzer.core.TestCategory;
import io.promptfuzzer.llm.ChatResponse;

/** Shared test data: the bundled smart-home example task. */
public final class TestFixtures {

    private TestFixtures() {
    }

    public static TaskSpec smartHome() {
        return Json.read(TestFixtures.class.getResourceAsStream("/examples/smart-home-commands.json"), TaskSpec.class);
    }

    public static TaskSpec calendar() {
        return Json.read(TestFixtures.class.getResourceAsStream("/examples/calendar-events.json"), TaskSpec.class);
    }

    public static JsonNode json(String s) {
        return Json.parse(s);
    }

    public static TestCase match(TestCategory category, String input, String expected) {
        return new TestCase("t-" + Math.abs(input.hashCode()), category, input, ExpectedBehavior.MATCH_EXPECTED,
                Json.parse(expected), null, TestCase.Origin.AI_GENERATED, TestCase.ExpectationSource.SEED, 0);
    }

    public static TestCase expecting(ExpectedBehavior behavior, TestCategory category, String input) {
        return new TestCase("t-" + Math.abs(input.hashCode()), category, input, behavior, null, null,
                TestCase.Origin.AI_GENERATED, TestCase.ExpectationSource.SEED, 0);
    }

    public static ChatResponse reply(String text) {
        return new ChatResponse(text, 100, 20, 250, "end_turn");
    }
}
