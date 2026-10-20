package io.promptfuzzer.core;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Describes the AI application under test: its instructions, the shape of its structured output, and seed
 * examples with known-correct answers.
 *
 * @param id                  stable identifier, used for file names
 * @param name                human-readable name
 * @param description         what the application does, in plain language (given to the generator and judge)
 * @param systemPrompt        the instructions the target model receives; this is what is being tested
 * @param outputSchema        JSON Schema (subset) every response must satisfy; may be null
 * @param clarificationOutput JSON pattern a response must contain to count as "asked for clarification",
 *                            e.g. {@code {"action": "clarify"}}; may be null, in which case an AI judge decides
 * @param refusalOutput       JSON pattern a response must contain to count as a refusal; may be null
 * @param semanticFields      top-level fields holding free text (titles, messages) compared by meaning rather
 *                            than exact value
 * @param ignoredFields       top-level fields excluded from comparison entirely
 * @param examples            seed examples with expected outputs
 * @param comparison          normalization rules for deterministic comparison
 */
public record TaskSpec(
        String id,
        String name,
        String description,
        String systemPrompt,
        JsonNode outputSchema,
        JsonNode clarificationOutput,
        JsonNode refusalOutput,
        List<String> semanticFields,
        List<String> ignoredFields,
        List<Example> examples,
        ComparisonOptions comparison) {

    public TaskSpec {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(systemPrompt, "systemPrompt");
        semanticFields = semanticFields == null ? List.of() : List.copyOf(semanticFields);
        ignoredFields = ignoredFields == null ? List.of() : List.copyOf(ignoredFields);
        examples = examples == null ? List.of() : List.copyOf(examples);
        comparison = comparison == null ? ComparisonOptions.DEFAULT : comparison;
        if (id == null || id.isBlank()) {
            id = Ids.slug(name);
        }
    }

    /** Checks the spec is complete enough to generate and run tests. Returns problems, empty when valid. */
    public List<String> validate() {
        List<String> problems = new ArrayList<>();
        if (name.isBlank()) {
            problems.add("Task name is required.");
        }
        if (systemPrompt.isBlank()) {
            problems.add("System prompt is required.");
        }
        if (examples.isEmpty()) {
            problems.add("At least one seed example is required.");
        }
        for (int i = 0; i < examples.size(); i++) {
            Example e = examples.get(i);
            if (e.input() == null || e.input().isBlank()) {
                problems.add("Example " + (i + 1) + " has no input.");
            }
            if (e.expectedOutput() == null || e.expectedOutput().isNull()) {
                problems.add("Example " + (i + 1) + " has no expected output.");
            }
        }
        if (outputSchema != null && !outputSchema.isObject()) {
            problems.add("Output schema must be a JSON object.");
        }
        return problems;
    }

    /** A seed example: an input and the output a correct application produces for it. */
    public record Example(String input, JsonNode expectedOutput) {
    }
}
