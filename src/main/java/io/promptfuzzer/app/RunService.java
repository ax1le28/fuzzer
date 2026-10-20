package io.promptfuzzer.app;

import io.promptfuzzer.analysis.AiFailureAnalyst;
import io.promptfuzzer.analysis.FailureAnalysis;
import io.promptfuzzer.analysis.ReportBuilder;
import io.promptfuzzer.analysis.RunReport;
import io.promptfuzzer.core.Ids;
import io.promptfuzzer.core.TestSuite;
import io.promptfuzzer.evaluation.AiJudge;
import io.promptfuzzer.execution.TestResult;
import io.promptfuzzer.execution.TestRunner;
import io.promptfuzzer.llm.Budget;
import io.promptfuzzer.llm.ChatModel;
import io.promptfuzzer.llm.ModelConfig;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Starts runs in the background, exposes live progress, and persists finished reports.
 */
public final class RunService {

    private final AppConfig config;
    private final ModelProvider models;
    private final FileStore<TestSuite> suites;
    private final FileStore<RunReport> runs;
    private final Map<String, LiveRun> live = new ConcurrentHashMap<>();

    public RunService(AppConfig config, ModelProvider models, FileStore<TestSuite> suites, FileStore<RunReport> runs) {
        this.config = config;
        this.models = models;
        this.suites = suites;
        this.runs = runs;
    }

    /**
     * @param judge       optional model for semantic checks; null keeps evaluation fully offline
     * @param aiAnalysis  ask the judge model to write failure insights after the run
     * @param maxCostUsd  budget for this run; capped at the server's {@code PF_MAX_COST_USD}
     */
    public record Request(String suiteId, List<ModelConfig> targets, int repeats, int concurrency,
                          Integer maxOutputTokens, ModelConfig judge, boolean aiAnalysis, Double maxCostUsd) {
    }

    public CostEstimator.Estimate estimate(Request request) {
        TestSuite suite = loadSuite(request.suiteId());
        List<CostEstimator.Target> targets = request.targets().stream()
                .map(c -> {
                    ChatModel m = models.describe(c);
                    return new CostEstimator.Target(m.id(), m.pricing());
                }).toList();
        CostEstimator.Target judge = null;
        if (request.judge() != null) {
            ChatModel j = models.describe(request.judge());
            judge = new CostEstimator.Target(j.id(), j.pricing());
        }
        return CostEstimator.estimate(suite, targets, Math.max(1, request.repeats()), maxOutputTokens(request), judge);
    }

    public RunReport start(Request request) {
        TestSuite suite = loadSuite(request.suiteId());
        if (request.targets() == null || request.targets().isEmpty()) {
            throw new IllegalArgumentException("Choose at least one target model");
        }
        if (request.targets().size() > 4) {
            throw new IllegalArgumentException("At most 4 targets can be compared in one run");
        }
        if (suite.cases().size() > config.maxTests()) {
            throw new IllegalArgumentException("Suite has " + suite.cases().size() + " tests; the limit is " + config.maxTests());
        }
        TestRunner.Options options = new TestRunner.Options(request.repeats(), request.concurrency(), maxOutputTokens(request));
        int planned = TestRunner.plannedCalls(suite, request.targets().size(), request.repeats());
        double cap = request.maxCostUsd() == null ? config.maxCostUsd() : Math.min(request.maxCostUsd(), config.maxCostUsd());
        if (cap <= 0) {
            throw new IllegalArgumentException("Budget must be positive");
        }
        int judgeHeadroom = request.judge() == null ? 0 : planned + 1;
        int requestCap = Math.min(config.maxRequests(), planned * 2 + judgeHeadroom + 4);
        if (planned > config.maxRequests()) {
            throw new IllegalArgumentException(planned + " calls planned, more than the limit of " + config.maxRequests()
                    + ". Reduce repeats, targets or suite size, or raise PF_MAX_REQUESTS.");
        }
        Budget budget = new Budget(requestCap, cap);

        List<ChatModel> targets = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (ModelConfig c : request.targets()) {
            ChatModel m = models.create(c, budget);
            if (!ids.add(m.id())) {
                throw new IllegalArgumentException("Target " + m.id() + " is listed twice");
            }
            targets.add(m);
        }
        AiJudge judge = request.judge() == null ? null : new AiJudge(models.create(request.judge(), budget));

        String id = Ids.timestamped("run");
        RunReport.RunSettings settings = new RunReport.RunSettings(targets.stream().map(ChatModel::id).toList(),
                request.repeats(), request.concurrency(), options.maxOutputTokens(),
                judge == null ? null : judge.modelId(), cap, requestCap);
        LiveRun run = new LiveRun(id, suite, settings, planned, budget, judge, request.judge());
        live.put(id, run);

        Thread.ofVirtual().name("run-" + id).start(() -> execute(run, targets, options, request.aiAnalysis()));
        return run.snapshot(false);
    }

    private void execute(LiveRun run, List<ChatModel> targets, TestRunner.Options options, boolean aiAnalysis) {
        try {
            List<TestResult> results = new TestRunner(targets, run.judge, options)
                    .run(run.suite, run.cancelled, (result, done, total) -> {
                        run.results.add(result);
                        run.completed.set(done);
                    });
            FailureAnalysis analysis = ReportBuilder.analyze(results);
            if (aiAnalysis && run.judge != null && !analysis.clusters().isEmpty() && !run.cancelled.get()) {
                run.message = "Writing AI failure analysis…";
                analysis = analysis.withAiInsights(new AiFailureAnalyst(
                        models.create(run.judgeConfig, run.budget)).analyze(run.suite.task(), analysis, results));
            }
            run.finish(results, analysis, run.cancelled.get() ? RunReport.Status.CANCELLED : RunReport.Status.COMPLETED,
                    budgetMessage(run));
        } catch (RuntimeException e) {
            run.finish(new ArrayList<>(run.results), ReportBuilder.analyze(new ArrayList<>(run.results)),
                    RunReport.Status.FAILED, "Run failed: " + e.getMessage());
        }
        runs.save(run.id, run.snapshot(true));
        live.remove(run.id);
    }

    private static String budgetMessage(LiveRun run) {
        long skipped = run.results.stream().filter(r -> r.error() != null && r.error().startsWith("Skipped")).count();
        if (skipped > 0) {
            return skipped + " call(s) skipped because the budget was reached ($"
                    + String.format("%.4f", run.budget.spentUsd()) + " of $" + run.settings.maxCostUsd() + ").";
        }
        return null;
    }

    public Optional<RunReport> get(String id, boolean includeResults) {
        LiveRun run = live.get(id);
        if (run != null) {
            return Optional.of(run.snapshot(includeResults));
        }
        return runs.load(id);
    }

    public List<RunReport> list() {
        List<RunReport> out = new ArrayList<>();
        live.values().forEach(r -> out.add(r.snapshot(false)));
        runs.list().forEach(r -> out.add(withoutDetails(r)));
        return out;
    }

    public boolean cancel(String id) {
        LiveRun run = live.get(id);
        if (run == null) {
            return false;
        }
        run.cancelled.set(true);
        run.message = "Cancelling: waiting for in-flight calls…";
        return true;
    }

    public boolean delete(String id) {
        return !live.containsKey(id) && runs.delete(id);
    }

    private static RunReport withoutDetails(RunReport r) {
        return new RunReport(r.id(), r.suiteId(), r.suiteName(), r.status(), r.startedAt(), r.finishedAt(), r.settings(),
                r.completed(), r.total(), r.models(), List.of(), null, r.judge(), null, r.message(), r.demo());
    }

    private TestSuite loadSuite(String id) {
        return suites.load(id).orElseThrow(() -> new IllegalArgumentException("Unknown suite " + id));
    }

    private static int maxOutputTokens(Request r) {
        return r.maxOutputTokens() == null ? 4096 : r.maxOutputTokens();
    }

    /** Mutable state of a run in progress. */
    private static final class LiveRun {
        final String id;
        final TestSuite suite;
        final RunReport.RunSettings settings;
        final int total;
        final Budget budget;
        final AiJudge judge;
        final ModelConfig judgeConfig;
        final Instant startedAt = Instant.now();
        final AtomicBoolean cancelled = new AtomicBoolean();
        final AtomicInteger completed = new AtomicInteger();
        final List<TestResult> results = new CopyOnWriteArrayList<>();
        volatile String message;
        volatile RunReport.Status status = RunReport.Status.RUNNING;
        volatile List<TestResult> finalResults;
        volatile FailureAnalysis analysis;
        volatile Instant finishedAt;

        LiveRun(String id, TestSuite suite, RunReport.RunSettings settings, int total, Budget budget, AiJudge judge,
                ModelConfig judgeConfig) {
            this.id = id;
            this.suite = suite;
            this.settings = settings;
            this.total = total;
            this.budget = budget;
            this.judge = judge;
            this.judgeConfig = judgeConfig;
        }

        void finish(List<TestResult> results, FailureAnalysis analysis, RunReport.Status status, String message) {
            this.finalResults = results;
            this.analysis = analysis;
            this.message = message;
            this.finishedAt = Instant.now();
            this.status = status;
        }

        RunReport snapshot(boolean includeResults) {
            List<TestResult> current = finalResults != null ? finalResults : new ArrayList<>(results);
            RunReport.JudgeUsage judgeUsage = judge == null ? null
                    : new RunReport.JudgeUsage(judge.modelId(), judge.calls(), judge.inputTokens(), judge.outputTokens(), judge.costUsd());
            List<TestResult> shown = includeResults ? current
                    : current.subList(Math.max(0, current.size() - 12), current.size());
            return new RunReport(id, suite.id(), suite.name(), status, startedAt, finishedAt, settings,
                    finalResults != null ? current.size() : completed.get(), total,
                    ReportBuilder.summarize(settings.targets(), current), List.copyOf(shown),
                    includeResults ? (analysis != null ? analysis : ReportBuilder.analyze(current)) : null,
                    judgeUsage, includeResults ? suite : null, message, false);
        }
    }
}
