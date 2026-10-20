package io.promptfuzzer.generation;

import io.promptfuzzer.core.ExpectedBehavior;
import io.promptfuzzer.core.TaskSpec;
import io.promptfuzzer.core.TestCase;
import io.promptfuzzer.core.TestCategory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Deterministic, meaning-preserving perturbations. Given the same seed they always produce the same inputs,
 * so part of every suite is reproducible without any API call and cannot be biased by a generator model.
 */
public final class RuleBasedMutator {

    private static final Map<Character, String> KEYBOARD_NEIGHBOURS = Map.ofEntries(
            Map.entry('a', "qwsz"), Map.entry('b', "vghn"), Map.entry('c', "xdfv"), Map.entry('d', "serfcx"),
            Map.entry('e', "wsdr"), Map.entry('f', "drtgvc"), Map.entry('g', "ftyhbv"), Map.entry('h', "gyujnb"),
            Map.entry('i', "ujko"), Map.entry('j', "huikmn"), Map.entry('k', "jiolm"), Map.entry('l', "kop"),
            Map.entry('m', "njk"), Map.entry('n', "bhjm"), Map.entry('o', "iklp"), Map.entry('p', "ol"),
            Map.entry('q', "wa"), Map.entry('r', "edft"), Map.entry('s', "awedxz"), Map.entry('t', "rfgy"),
            Map.entry('u', "yhji"), Map.entry('v', "cfgb"), Map.entry('w', "qase"), Map.entry('x', "zsdc"),
            Map.entry('y', "tghu"), Map.entry('z', "asx"));

    private static final List<String> FILLER_BEFORE = List.of(
            "Ugh, long day at work and the dog still needs walking. Anyway,",
            "My sister is visiting next week so I'm trying to get organised. ",
            "Quick one before I forget (I'm on the train and the signal is bad):",
            "So the weather app says rain again, typical. Unrelated, but");
    private static final List<String> FILLER_AFTER = List.of(
            " Also remind me later to call the plumber, but that's not for now.",
            " By the way, the new coffee machine is amazing.",
            " Thanks! Oh and ignore the noise, the neighbours are renovating.");

    private final long seed;

    public RuleBasedMutator(long seed) {
        this.seed = seed;
    }

    /** One baseline plus three deterministic variants per seed example. */
    public List<TestCase> mutate(TaskSpec task) {
        List<TestCase> out = new ArrayList<>();
        for (int i = 0; i < task.examples().size(); i++) {
            TaskSpec.Example example = task.examples().get(i);
            Random random = new Random(seed * 31 + i);
            String input = example.input();
            out.add(rule("seed-" + i, TestCategory.BASELINE, input, example, i, "Unmodified seed example."));
            out.add(rule("rule-typo-" + i, TestCategory.TYPOS, injectTypos(input, random, 0.12), example, i,
                    "Keyboard-neighbour substitutions, dropped and swapped letters (seeded)."));
            out.add(rule("rule-casual-" + i, TestCategory.TYPOS, flatten(input), example, i,
                    "Lower-cased with punctuation stripped, as typed quickly on a phone."));
            out.add(rule("rule-context-" + i, TestCategory.IRRELEVANT_CONTEXT, addNoise(input, random), example, i,
                    "Request surrounded by unrelated chatter."));
        }
        return out;
    }

    private static TestCase rule(String id, TestCategory category, String input, TaskSpec.Example example,
                                 int seedIndex, String rationale) {
        return new TestCase(id, category, input, ExpectedBehavior.MATCH_EXPECTED, example.expectedOutput(),
                rationale, category == TestCategory.BASELINE ? TestCase.Origin.SEED : TestCase.Origin.RULE_BASED,
                TestCase.ExpectationSource.SEED, seedIndex);
    }

    /**
     * Applies typos to letters inside lower-case words only. Digits and capitalised words (likely names) are
     * left intact so quantities, times and proper nouns survive and the expected output remains correct.
     */
    static String injectTypos(String input, Random random, double rate) {
        boolean[] protect = protectedPositions(input);
        StringBuilder sb = new StringBuilder();
        int applied = 0;
        int i = 0;
        while (i < input.length()) {
            char c = input.charAt(i);
            boolean inner = i > 0 && i < input.length() - 1
                    && Character.isLetter(c) && Character.isLetter(input.charAt(i - 1)) && !protect[i];
            if (!inner || random.nextDouble() >= rate) {
                sb.append(c);
                i++;
                continue;
            }
            applied++;
            switch (random.nextInt(3)) {
                case 0 -> {
                    String neighbours = KEYBOARD_NEIGHBOURS.get(Character.toLowerCase(c));
                    sb.append(neighbours == null ? c : neighbours.charAt(random.nextInt(neighbours.length())));
                    i++;
                }
                case 1 -> i++; // drop the letter
                default -> {
                    char next = input.charAt(i + 1);
                    if (Character.isLetter(next)) {
                        sb.append(next).append(c);
                        i += 2;
                    } else {
                        sb.append(c).append(c);
                        i++;
                    }
                }
            }
        }
        if (applied == 0) {
            // Guarantee at least one perturbation so the test never duplicates the baseline.
            for (int j = input.length() / 2; j < input.length(); j++) {
                if (Character.isLetter(input.charAt(j)) && !protect[j]) {
                    return input.substring(0, j) + input.charAt(j) + input.substring(j);
                }
            }
        }
        return sb.toString();
    }

    /** Marks every letter of words that start with a capital, which are likely names. */
    private static boolean[] protectedPositions(String input) {
        boolean[] protect = new boolean[input.length()];
        int i = 0;
        while (i < input.length()) {
            if (!Character.isLetter(input.charAt(i))) {
                i++;
                continue;
            }
            int start = i;
            while (i < input.length() && Character.isLetter(input.charAt(i))) {
                i++;
            }
            if (Character.isUpperCase(input.charAt(start))) {
                for (int j = start; j < i; j++) {
                    protect[j] = true;
                }
            }
        }
        return protect;
    }

    static String flatten(String input) {
        return input.toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{Punct}&&[^:%°.\\-]]", "")
                .replaceAll("\\.(?!\\d)", "")
                .replaceAll("\\s+", " ")
                .strip();
    }

    private static String addNoise(String input, Random random) {
        String before = FILLER_BEFORE.get(random.nextInt(FILLER_BEFORE.size()));
        String after = FILLER_AFTER.get(random.nextInt(FILLER_AFTER.size()));
        String request = Character.toLowerCase(input.charAt(0)) + input.substring(1);
        return before.strip() + " " + request + after;
    }
}
