package io.promptfuzzer.generation;

import io.promptfuzzer.core.ExpectedBehavior;
import io.promptfuzzer.core.TaskSpec;
import io.promptfuzzer.core.TestCase;
import io.promptfuzzer.core.TestCategory;
import io.promptfuzzer.core.TestSuite;
import io.promptfuzzer.llm.ChatModel;
import io.promptfuzzer.llm.ChatRequest;
import io.promptfuzzer.llm.ChatResponse;
import io.promptfuzzer.llm.Pricing;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;

import static io.promptfuzzer.TestFixtures.calendar;
import static io.promptfuzzer.TestFixtures.smartHome;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationTest {

    private final TaskSpec task = smartHome();

    @Test
    void ruleBasedMutationIsReproducible() {
        List<TestCase> a = new RuleBasedMutator(7).mutate(task);
        List<TestCase> b = new RuleBasedMutator(7).mutate(task);
        List<TestCase> c = new RuleBasedMutator(8).mutate(task);
        assertEquals(a, b);
        assertNotEquals(a.stream().map(TestCase::input).toList(), c.stream().map(TestCase::input).toList());
        assertEquals(task.examples().size() * 4, a.size());
        assertTrue(a.stream().allMatch(t -> t.expectedBehavior() == ExpectedBehavior.MATCH_EXPECTED));
    }

    @Test
    void typosNeverTouchDigitsOrCapitalisedNames() {
        for (int seed = 0; seed < 200; seed++) {
            String out = RuleBasedMutator.injectTypos("Book a call with Marco and Lena at 9:30 for 45 minutes", new Random(seed), 0.3);
            assertTrue(out.contains("Marco") && out.contains("Lena"), out);
            assertTrue(out.contains("9:30") && out.contains("45"), out);
            assertNotEquals("Book a call with Marco and Lena at 9:30 for 45 minutes", out, "always perturbed");
        }
    }

    @Test
    void flattenKeepsNumbersAndTimes() {
        assertEquals("set bedroom to 21.5 at 9:30", RuleBasedMutator.flatten("Set the bedroom to 21.5, at 9:30!"));
    }

    @Test
    void parsesGeneratedCasesAndRejectsBadOnes() {
        String reply = """
                Here are the cases:
                {"cases": [
                  {"seed_index": 0, "input": "kitchen lights on pls", "expected_behavior": "MATCH_EXPECTED", "expected_output": null, "rationale": "slang"},
                  {"seed_index": 0, "input": "don't turn on the kitchen lights, turn them off", "expected_behavior": "MATCH_EXPECTED",
                   "expected_output": {"action": "turn_off", "device": "lights", "room": "kitchen"}, "rationale": "negation"},
                  {"seed_index": 1, "input": "set it to 21", "expected_behavior": "ask_clarification", "rationale": "no room"},
                  {"seed_index": 0, "input": "", "expected_behavior": "MATCH_EXPECTED"},
                  {"seed_index": 0, "input": "lights in the garage", "expected_behavior": "MATCH_EXPECTED",
                   "expected_output": {"action": "turn_on", "device": "lights", "room": "garage"}},
                  {"seed_index": 0, "input": "hmm", "expected_behavior": "DANCE"}
                ]}""";
        List<String> warnings = new ArrayList<>();
        List<TestCase> cases = AiTestGenerator.parse(task, TestCategory.NEGATION, reply, warnings);
        assertEquals(3, cases.size(), warnings.toString());
        assertEquals(3, warnings.size(), warnings.toString());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("violates the schema")), "invented 'garage' room must be rejected");

        assertEquals(TestCase.ExpectationSource.SEED, cases.get(0).expectationSource());
        assertEquals(task.examples().get(0).expectedOutput(), cases.get(0).expectedOutput());
        assertEquals(TestCase.ExpectationSource.AI_PROPOSED, cases.get(1).expectationSource());
        assertEquals(ExpectedBehavior.ASK_CLARIFICATION, cases.get(2).expectedBehavior());
        assertTrue(cases.stream().allMatch(c -> c.origin() == TestCase.Origin.AI_GENERATED));
    }

    @Test
    void nonJsonReplyIsDiscardedWithWarning() {
        List<String> warnings = new ArrayList<>();
        assertTrue(AiTestGenerator.parse(task, TestCategory.SLANG, "I cannot help with that.", warnings).isEmpty());
        assertEquals(1, warnings.size());
    }

    @Test
    void generatorPromptFencesUserContent() {
        String prompt = AiTestGenerator.userPrompt(task, TestCategory.INJECTION, 3);
        assertTrue(prompt.contains("untrusted data"));
        assertTrue(prompt.contains("Turn on the kitchen lights"));
        assertTrue(prompt.matches("(?s).*<data-[0-9a-f]{12} label=\"seed-input\">.*"));
    }

    @Test
    void suiteBuilderCombinesRulesAndAiBatchesAndDeduplicates() {
        ChatModel generator = new ChatModel() {
            @Override
            public String id() {
                return "fake-generator";
            }

            @Override
            public Optional<Pricing> pricing() {
                return Optional.of(new Pricing(1, 1));
            }

            @Override
            public ChatResponse complete(ChatRequest request) {
                // Echoes a seed input, which must be removed as a duplicate of the baseline.
                return new ChatResponse("""
                        {"cases": [
                          {"seed_index": 0, "input": "Turn on the kitchen lights", "expected_behavior": "MATCH_EXPECTED"},
                          {"seed_index": 2, "input": "pls lock hallway door", "expected_behavior": "MATCH_EXPECTED"}
                        ]}""", 1000, 500, 10, "end_turn");
            }
        };
        TestSuite suite = new SuiteBuilder(new AiTestGenerator(generator), 150).build(task,
                new SuiteBuilder.Options(EnumSet.of(TestCategory.SLANG, TestCategory.TYPOS), 2, true, 42));
        long ruleCount = suite.cases().stream().filter(c -> c.origin() != TestCase.Origin.AI_GENERATED).count();
        assertEquals(20, ruleCount);
        assertEquals(21, suite.cases().size(), "one new unique AI input; the echo and the second batch's copy are deduplicated");
        assertEquals(2000, suite.generation().inputTokens());
        assertEquals("fake-generator", suite.generation().generatorModel());
        assertTrue(suite.generation().warnings().stream().anyMatch(w -> w.contains("duplicate")));
        assertEquals(suite.cases().size(), suite.cases().stream().map(TestCase::id).distinct().count(), "ids are unique");
    }

    @Test
    void suiteBuilderWithoutGeneratorIsRuleBasedAndOffline() {
        TestSuite suite = new SuiteBuilder(null, 150).build(calendar(),
                new SuiteBuilder.Options(EnumSet.of(TestCategory.NEGATION), 4, true, 1));
        assertEquals(12, suite.cases().size());
        assertTrue(suite.generation().warnings().get(0).contains("No generator"));
    }

    @Test
    void suiteBuilderRejectsIncompleteTask() {
        TaskSpec broken = new TaskSpec(null, "x", null, "", null, null, null, null, null, List.of(), null);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new SuiteBuilder(null, 10).build(broken, new SuiteBuilder.Options(EnumSet.noneOf(TestCategory.class), 0, true, 1)));
        assertTrue(e.getMessage().contains("System prompt"));
        assertFalse(e.getMessage().isBlank());
    }

    @Test
    void suiteIsCappedAtMaxTests() {
        TestSuite suite = new SuiteBuilder(null, 7).build(task, new SuiteBuilder.Options(EnumSet.noneOf(TestCategory.class), 0, true, 1));
        assertEquals(7, suite.cases().size());
    }
}
