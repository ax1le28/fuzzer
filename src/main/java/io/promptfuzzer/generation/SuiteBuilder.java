package io.promptfuzzer.generation;

import io.promptfuzzer.core.Ids;
import io.promptfuzzer.core.TaskSpec;
import io.promptfuzzer.core.TestCase;
import io.promptfuzzer.core.TestCategory;
import io.promptfuzzer.core.TestSuite;
import io.promptfuzzer.llm.LlmException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Assembles a {@link TestSuite} from rule-based mutations and AI-generated batches. */
public final class SuiteBuilder {

    private final AiTestGenerator generator;
    private final int maxTests;

    /** @param generator may be null to build a rule-based suite with no API calls */
    public SuiteBuilder(AiTestGenerator generator, int maxTests) {
        this.generator = generator;
        this.maxTests = maxTests;
    }

    /**
     * @param categories   categories to ask the generator for (BASELINE is ignored here)
     * @param perCategory  cases requested per category
     * @param includeRules include deterministic mutations and seed baselines
     * @param seed         seed for rule-based mutations
     */
    public record Options(Set<TestCategory> categories, int perCategory, boolean includeRules, long seed) {
        public Options {
            categories = categories == null ? Set.of() : Set.copyOf(categories);
            if (perCategory < 0 || perCategory > 50) {
                throw new IllegalArgumentException("perCategory must be between 0 and 50");
            }
        }
    }

    public TestSuite build(TaskSpec task, Options options) {
        List<String> problems = task.validate();
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(String.join(" ", problems));
        }
        List<TestCase> cases = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        long inTokens = 0;
        long outTokens = 0;

        if (options.includeRules()) {
            cases.addAll(new RuleBasedMutator(options.seed()).mutate(task));
        }

        List<TestCategory> aiCategories = options.categories().stream()
                .filter(c -> c != TestCategory.BASELINE)
                .sorted()
                .toList();
        if (generator != null && options.perCategory() > 0 && !aiCategories.isEmpty()) {
            try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
                List<Future<AiTestGenerator.Batch>> futures = new ArrayList<>();
                for (TestCategory category : aiCategories) {
                    futures.add(pool.submit(() -> generator.generate(task, category, options.perCategory())));
                }
                for (int i = 0; i < futures.size(); i++) {
                    try {
                        AiTestGenerator.Batch batch = futures.get(i).get();
                        cases.addAll(batch.cases());
                        warnings.addAll(batch.warnings());
                        inTokens += batch.inputTokens();
                        outTokens += batch.outputTokens();
                    } catch (ExecutionException e) {
                        Throwable cause = e.getCause();
                        if (cause instanceof LlmException llm && !llm.retryable() && llm.status() == 401) {
                            throw llm;
                        }
                        warnings.add(aiCategories.get(i).displayName() + ": generation failed (" + cause.getMessage() + ")");
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Generation interrupted", e);
            }
        } else if (generator == null && !aiCategories.isEmpty() && options.perCategory() > 0) {
            warnings.add("No generator model configured: only rule-based cases were created.");
        }

        List<TestCase> unique = dedupe(cases, warnings);
        if (unique.size() > maxTests) {
            warnings.add("Suite capped at " + maxTests + " tests (" + (unique.size() - maxTests) + " dropped).");
            unique = unique.subList(0, maxTests);
        }
        if (unique.isEmpty()) {
            throw new IllegalStateException("No test cases could be generated. " + String.join(" ", warnings));
        }

        Double cost = generator == null ? null : generator.costUsd(inTokens, outTokens);
        String generatorId = generator == null ? null : generator.modelId();
        TestSuite.GenerationInfo info = new TestSuite.GenerationInfo(generatorId, options.seed(), inTokens, outTokens,
                cost, warnings);
        return new TestSuite(Ids.timestamped("suite"), task.name(), task, unique, null, info);
    }

    /** Drops cases whose normalized input repeats an earlier one, then renumbers ids to be unique. */
    static List<TestCase> dedupe(List<TestCase> cases, List<String> warnings) {
        Set<String> seen = new HashSet<>();
        Set<String> ids = new LinkedHashSet<>();
        List<TestCase> out = new ArrayList<>();
        int dropped = 0;
        for (TestCase c : cases) {
            String key = c.input().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
            if (!seen.add(key)) {
                dropped++;
                continue;
            }
            String id = c.id();
            int n = 2;
            while (!ids.add(id)) {
                id = c.id() + "-" + n++;
            }
            out.add(id.equals(c.id()) ? c : new TestCase(id, c.category(), c.input(), c.expectedBehavior(),
                    c.expectedOutput(), c.rationale(), c.origin(), c.expectationSource(), c.seedIndex()));
        }
        if (dropped > 0) {
            warnings.add(dropped + " duplicate input(s) removed.");
        }
        return out;
    }
}
