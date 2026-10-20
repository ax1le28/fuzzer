package io.promptfuzzer.core;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Objects;

/**
 * One generated test.
 *
 * @param id               unique within its suite
 * @param category         which perturbation this test exercises
 * @param input            the user input sent to the application under test (untrusted data)
 * @param expectedBehavior what a correct application does
 * @param expectedOutput   the exact expected output when {@code expectedBehavior} is MATCH_EXPECTED
 * @param rationale        why the expectation holds, as written by the generator
 * @param origin           how the test was produced
 * @param expectationSource where {@code expectedOutput} came from; AI-proposed expectations deserve review
 * @param seedIndex        which seed example this test was derived from
 */
public record TestCase(
        String id,
        TestCategory category,
        String input,
        ExpectedBehavior expectedBehavior,
        JsonNode expectedOutput,
        String rationale,
        Origin origin,
        ExpectationSource expectationSource,
        int seedIndex) {

    public TestCase {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(expectedBehavior, "expectedBehavior");
        origin = origin == null ? Origin.AI_GENERATED : origin;
        expectationSource = expectationSource == null ? ExpectationSource.SEED : expectationSource;
        if (expectedBehavior == ExpectedBehavior.MATCH_EXPECTED && (expectedOutput == null || expectedOutput.isNull())) {
            throw new IllegalArgumentException("Test " + id + " expects a matching output but has none");
        }
    }

    public enum Origin {
        /** A seed example supplied by the user. */
        SEED,
        /** Produced by deterministic, seeded mutation rules (reproducible without an API call). */
        RULE_BASED,
        /** Produced by the generator model. */
        AI_GENERATED
    }

    public enum ExpectationSource {
        /** Copied from a user-supplied seed example. */
        SEED,
        /** Proposed by the generator model because the variation changes the meaning. */
        AI_PROPOSED,
        /** Written or edited by a person in the suite editor. */
        USER
    }
}
