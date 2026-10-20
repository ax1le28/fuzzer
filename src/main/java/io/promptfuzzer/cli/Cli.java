package io.promptfuzzer.cli;

import io.promptfuzzer.analysis.FailureAnalysis;
import io.promptfuzzer.analysis.ModelSummary;
import io.promptfuzzer.analysis.ReportBuilder;
import io.promptfuzzer.analysis.RunReport;
import io.promptfuzzer.app.AppConfig;
import io.promptfuzzer.app.DemoBuilder;
import io.promptfuzzer.app.FileStore;
import io.promptfuzzer.app.GenerationService;
import io.promptfuzzer.app.ModelProvider;
import io.promptfuzzer.app.RunService;
import io.promptfuzzer.core.Ids;
import io.promptfuzzer.core.Json;
import io.promptfuzzer.core.TaskSpec;
import io.promptfuzzer.core.TestCategory;
import io.promptfuzzer.core.TestSuite;
import io.promptfuzzer.evaluation.AiJudge;
import io.promptfuzzer.execution.TestResult;
import io.promptfuzzer.execution.TestRunner;
import io.promptfuzzer.generation.AiTestGenerator;
import io.promptfuzzer.generation.SuiteBuilder;
import io.promptfuzzer.llm.Budget;
import io.promptfuzzer.llm.ChatModel;
import io.promptfuzzer.llm.ModelConfig;
import io.promptfuzzer.llm.ModelFactory;
import io.promptfuzzer.llm.ProviderPreset;
import io.promptfuzzer.web.WebServer;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * Command-line interface. The {@code run} command exits non-zero when the pass rate falls below
 * {@code --min-pass-rate}, so a suite can gate a CI pipeline.
 */
public final class Cli {

    private static final String USAGE = """
            PromptFuzzer: fuzz testing for LLM applications

            Usage:
              promptfuzzer [serve]                       Start the dashboard on http://127.0.0.1:7070
              promptfuzzer generate --task FILE --out FILE [options]
                  --model ID           generator model (default: PF_DEFAULT_MODEL, else the first provider with a key)
                  --categories LIST    comma-separated, e.g. PARAPHRASE,NEGATION,AMBIGUITY (default: all)
                  --per-category N     AI cases per category (default 4; 0 = rule-based only)
                  --seed N             seed for rule-based mutations (default 42)
              promptfuzzer run --suite FILE --model ID[,ID...] [options]
                  --repeats N          send each test N times to measure consistency (default 1)
                  --concurrency N      parallel calls (default 4)
                  --judge ID           AI judge for free-text fields (default: none, heuristics only)
                  --max-cost USD       budget for this run (default and ceiling: PF_MAX_COST_USD)
                  --min-pass-rate R    exit with status 1 if any model's pass rate is below R (0..1)
                  --out FILE           write the full JSON report
              promptfuzzer demo [--out FILE]              Print the bundled sample report summary

            Models are Anthropic model ids unless prefixed with a provider:
              gemini:gemini-2.5-flash           Google Gemini free tier   (key: GEMINI_API_KEY)
              groq:llama-3.3-70b-versatile      Groq free tier            (key: GROQ_API_KEY)
              ollama:qwen2.5:7b                 local Ollama              (no key)
              openai:<model>@<base-url>[#KEY_ENV|#none]   any OpenAI-compatible server (key: OPENAI_API_KEY)
            Anthropic models read ANTHROPIC_API_KEY. Keys are only ever read from environment variables.
            """;

    private final Function<String, String> env;
    private final PrintStream out;
    private final PrintStream err;

    public Cli(Function<String, String> env, PrintStream out, PrintStream err) {
        this.env = env;
        this.out = out;
        this.err = err;
    }

    public int execute(String[] args) {
        String command = args.length == 0 ? "serve" : args[0];
        Map<String, String> opts;
        try {
            opts = parse(Arrays.copyOfRange(args, Math.min(1, args.length), args.length));
        } catch (IllegalArgumentException e) {
            err.println(e.getMessage());
            return 2;
        }
        try {
            return switch (command) {
                case "serve" -> serve();
                case "generate" -> generate(opts);
                case "run" -> run(opts);
                case "demo" -> demo(opts);
                case "help", "--help", "-h" -> {
                    out.print(USAGE);
                    yield 0;
                }
                default -> {
                    err.println("Unknown command: " + command);
                    err.print(USAGE);
                    yield 2;
                }
            };
        } catch (IllegalArgumentException | IllegalStateException e) {
            err.println("Error: " + e.getMessage());
            return 2;
        } catch (RuntimeException e) {
            err.println("Error: " + e.getMessage());
            return 3;
        }
    }

    private int serve() {
        AppConfig config = AppConfig.fromEnv(env);
        ModelFactory factory = new ModelFactory(env);
        ModelProvider provider = new ModelProvider(factory, config.requestsPerMinute());
        FileStore<TestSuite> suites = new FileStore<>(config.dataDir().resolve("suites"), TestSuite.class);
        FileStore<RunReport> runs = new FileStore<>(config.dataDir().resolve("runs"), RunReport.class);
        new WebServer(config, factory, suites,
                new GenerationService(config, provider, suites),
                new RunService(config, provider, suites, runs)).start();
        out.println();
        out.println("  PromptFuzzer is running at http://" + displayHost(config.host()) + ":" + config.port());
        List<String> keys = Arrays.stream(ProviderPreset.values())
                .filter(p -> p.keyEnv() != null && p.provider() != ModelConfig.Provider.ENDPOINT && factory.hasKey(p.keyEnv()))
                .map(ProviderPreset::label).toList();
        out.println("  API keys found: " + (keys.isEmpty() ? "none (demo, rule-based tests and local Ollama still work)" : String.join(", ", keys)));
        out.println("  Default model: " + config.defaultModel());
        out.println("  Per-run budget ceiling: $" + config.maxCostUsd() + ", data in " + config.dataDir().toAbsolutePath());
        out.println();
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return 0;
    }

    private int generate(Map<String, String> opts) throws IllegalArgumentException {
        AppConfig config = AppConfig.fromEnv(env);
        TaskSpec task = Json.read(Path.of(require(opts, "task")), TaskSpec.class);
        Path outFile = Path.of(require(opts, "out"));
        int perCategory = Integer.parseInt(opts.getOrDefault("per-category", "4"));
        Set<TestCategory> categories = opts.containsKey("categories")
                ? parseCategories(opts.get("categories"))
                : EnumSet.complementOf(EnumSet.of(TestCategory.BASELINE));
        AiTestGenerator generator = null;
        if (perCategory > 0) {
            ModelProvider provider = new ModelProvider(new ModelFactory(env), config.requestsPerMinute());
            Budget budget = new Budget(categories.size() * 4, config.maxCostUsd());
            generator = new AiTestGenerator(provider.create(modelConfig(opts.getOrDefault("model", config.defaultModel())), budget));
        }
        TestSuite suite = new SuiteBuilder(generator, config.maxTests()).build(task,
                new SuiteBuilder.Options(categories, perCategory, true, Long.parseLong(opts.getOrDefault("seed", "42"))));
        write(outFile, Json.pretty(suite));
        out.printf("Generated %d test cases → %s%n", suite.cases().size(), outFile);
        suite.generation().warnings().forEach(w -> out.println("  warning: " + w));
        return 0;
    }

    private int run(Map<String, String> opts) {
        AppConfig config = AppConfig.fromEnv(env);
        TestSuite suite = Json.read(Path.of(require(opts, "suite")), TestSuite.class);
        List<ModelConfig> targetConfigs = Arrays.stream(require(opts, "model").split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).map(Cli::modelConfig).toList();
        int repeats = Integer.parseInt(opts.getOrDefault("repeats", "1"));
        int concurrency = Integer.parseInt(opts.getOrDefault("concurrency", "4"));
        double maxCost = Math.min(config.maxCostUsd(), Double.parseDouble(opts.getOrDefault("max-cost", String.valueOf(config.maxCostUsd()))));

        int planned = TestRunner.plannedCalls(suite, targetConfigs.size(), repeats);
        if (planned > config.maxRequests()) {
            throw new IllegalArgumentException(planned + " calls planned; limit is " + config.maxRequests() + " (PF_MAX_REQUESTS)");
        }
        Budget budget = new Budget(Math.min(config.maxRequests(), planned * 3 + 4), maxCost);
        ModelProvider provider = new ModelProvider(new ModelFactory(env), config.requestsPerMinute());
        List<ChatModel> targets = targetConfigs.stream().map(c -> provider.create(c, budget)).toList();
        AiJudge judge = opts.containsKey("judge") ? new AiJudge(provider.create(modelConfig(opts.get("judge")), budget)) : null;

        out.printf("Running %d tests × %d model(s) × %d repeat(s) = %d calls (budget $%.2f)%n",
                suite.cases().size(), targets.size(), repeats, planned, maxCost);
        Instant started = Instant.now();
        TestRunner.Options options = new TestRunner.Options(repeats, concurrency, 4096);
        List<TestResult> results = new TestRunner(targets, judge, options).run(suite, new AtomicBoolean(),
                (r, done, total) -> {
                    if (done % 10 == 0 || done == total) {
                        out.printf("  %d/%d%n", done, total);
                    }
                });
        List<String> ids = targets.stream().map(ChatModel::id).toList();
        List<ModelSummary> summaries = ReportBuilder.summarize(ids, results);
        FailureAnalysis analysis = ReportBuilder.analyze(results);
        printSummary(summaries, analysis);
        out.printf("Spent ≈ $%.4f in %d requests%n", budget.spentUsd(), budget.requestsUsed());

        if (opts.containsKey("out")) {
            RunReport.RunSettings settings = new RunReport.RunSettings(ids, repeats, concurrency, 4096,
                    judge == null ? null : judge.modelId(), maxCost, budget.maxRequests());
            RunReport report = new RunReport(Ids.timestamped("run"), suite.id(), suite.name(), RunReport.Status.COMPLETED,
                    started, Instant.now(), settings, results.size(), results.size(), summaries, results, analysis,
                    judge == null ? null : new RunReport.JudgeUsage(judge.modelId(), judge.calls(), judge.inputTokens(),
                            judge.outputTokens(), judge.costUsd()), suite, null, false);
            write(Path.of(opts.get("out")), Json.pretty(report));
            out.println("Report written to " + opts.get("out"));
        }
        if (opts.containsKey("min-pass-rate")) {
            double min = Double.parseDouble(opts.get("min-pass-rate"));
            List<String> below = summaries.stream().filter(s -> s.passRate() < min).map(ModelSummary::model).toList();
            if (!below.isEmpty()) {
                err.printf("FAILED: pass rate below %.0f%% for %s%n", min * 100, String.join(", ", below));
                return 1;
            }
            out.printf("OK: every model meets the %.0f%% pass-rate threshold%n", min * 100);
        }
        return 0;
    }

    private int demo(Map<String, String> opts) {
        RunReport report = DemoBuilder.build();
        out.println(report.message());
        printSummary(report.models(), report.analysis());
        if (opts.containsKey("out")) {
            write(Path.of(opts.get("out")), Json.pretty(report));
        }
        return 0;
    }

    private void printSummary(List<ModelSummary> summaries, FailureAnalysis analysis) {
        out.println();
        out.printf("%-28s %7s %7s %7s %10s %9s %10s%n", "MODEL", "PASS", "FAIL", "ERROR", "PASS RATE", "P50 ms", "COST $");
        for (ModelSummary s : summaries) {
            out.printf("%-28s %7d %7d %7d %9.1f%% %9d %10s%n", truncate(s.model(), 28), s.passed(), s.failed(), s.errors(),
                    s.passRate() * 100, s.latency().p50(), s.costUsd() == null ? "n/a" : String.format(Locale.ROOT, "%.4f", s.costUsd()));
        }
        if (!analysis.clusters().isEmpty()) {
            out.println();
            out.println("Failure clusters:");
            for (FailureAnalysis.Cluster c : analysis.clusters()) {
                out.printf("  %-32s %4d  %s%n", c.label(), c.count(), c.categories().keySet());
            }
        }
        out.println();
    }

    /** {@code claude-x}, or {@code openai:model@http://host/v1} for OpenAI-compatible servers. */
    static ModelConfig modelConfig(String spec) {
        return ProviderPreset.parseSpec(spec);
    }

    static Set<TestCategory> parseCategories(String list) {
        Set<TestCategory> set = EnumSet.noneOf(TestCategory.class);
        for (String s : list.split(",")) {
            if (!s.isBlank()) {
                set.add(TestCategory.valueOf(s.trim().toUpperCase(Locale.ROOT)));
            }
        }
        return set;
    }

    static Map<String, String> parse(String[] args) {
        Map<String, String> map = new HashMap<>();
        List<String> list = new ArrayList<>(Arrays.asList(args));
        for (int i = 0; i < list.size(); i++) {
            String a = list.get(i);
            if (!a.startsWith("--")) {
                throw new IllegalArgumentException("Unexpected argument: " + a);
            }
            String key = a.substring(2);
            if (i + 1 >= list.size() || list.get(i + 1).startsWith("--")) {
                throw new IllegalArgumentException("Missing value for --" + key);
            }
            map.put(key, list.get(++i));
        }
        return map;
    }

    private static String require(Map<String, String> opts, String key) {
        String v = opts.get(key);
        if (v == null) {
            throw new IllegalArgumentException("--" + key + " is required");
        }
        return v;
    }

    private static void write(Path file, String content) {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.writeString(file, content);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot write " + file + ": " + e.getMessage(), e);
        }
    }

    private static String truncate(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n - 1) + "…";
    }

    private static String displayHost(String host) {
        return host.equals("0.0.0.0") ? "localhost" : host;
    }
}
