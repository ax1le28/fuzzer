package io.promptfuzzer.app;

import io.promptfuzzer.core.TaskSpec;
import io.promptfuzzer.core.TestCategory;
import io.promptfuzzer.core.TestSuite;
import io.promptfuzzer.generation.AiTestGenerator;
import io.promptfuzzer.generation.SuiteBuilder;
import io.promptfuzzer.llm.Budget;
import io.promptfuzzer.llm.ModelConfig;

import java.util.Set;

/** Generates and saves test suites. */
public final class GenerationService {

    private final AppConfig config;
    private final ModelProvider models;
    private final FileStore<TestSuite> suites;

    public GenerationService(AppConfig config, ModelProvider models, FileStore<TestSuite> suites) {
        this.config = config;
        this.models = models;
        this.suites = suites;
    }

    /**
     * @param generator null for a rule-based suite with no API calls
     */
    public record Request(TaskSpec task, ModelConfig generator, Set<TestCategory> categories, int perCategory,
                          boolean includeRules, Long seed) {
    }

    public TestSuite generate(Request request) {
        if (request.task() == null) {
            throw new IllegalArgumentException("A task is required");
        }
        AiTestGenerator generator = null;
        if (request.generator() != null && request.perCategory() > 0 && !request.categories().isEmpty()) {
            // One call per category; a little headroom for retries.
            Budget budget = new Budget(request.categories().size() * 4, config.maxCostUsd());
            generator = new AiTestGenerator(models.create(request.generator(), budget));
        }
        long seed = request.seed() == null ? 42L : request.seed();
        TestSuite suite = new SuiteBuilder(generator, config.maxTests()).build(request.task(),
                new SuiteBuilder.Options(request.categories(), request.perCategory(), request.includeRules(), seed));
        suites.save(suite.id(), suite);
        return suite;
    }
}
