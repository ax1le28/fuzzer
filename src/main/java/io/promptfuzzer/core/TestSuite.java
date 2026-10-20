package io.promptfuzzer.core;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * A frozen set of test cases for one task. Suites are saved to disk so the exact same inputs can be re-run
 * later or against other models, which is what makes comparisons meaningful.
 */
public record TestSuite(
        String id,
        String name,
        TaskSpec task,
        List<TestCase> cases,
        Instant createdAt,
        GenerationInfo generation) {

    public TestSuite {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(task, "task");
        cases = cases == null ? List.of() : List.copyOf(cases);
        name = name == null || name.isBlank() ? task.name() : name;
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }

    public TestSuite withCases(List<TestCase> newCases) {
        return new TestSuite(id, name, task, newCases, createdAt, generation);
    }

    /**
     * How the suite was produced.
     *
     * @param generatorModel model used for AI generation, or null if none was used
     * @param seed           random seed for rule-based mutations
     * @param inputTokens    tokens spent generating
     * @param outputTokens   tokens spent generating
     * @param costUsd        estimated generation cost, null when the model's price is unknown
     * @param warnings       generated cases that were discarded and why
     */
    public record GenerationInfo(
            String generatorModel,
            long seed,
            long inputTokens,
            long outputTokens,
            Double costUsd,
            List<String> warnings) {

        public GenerationInfo {
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }
}
