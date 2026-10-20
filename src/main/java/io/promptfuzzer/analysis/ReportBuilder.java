package io.promptfuzzer.analysis;

import io.promptfuzzer.core.Json;
import io.promptfuzzer.core.TestCategory;
import io.promptfuzzer.evaluation.Evaluation;
import io.promptfuzzer.evaluation.FailureType;
import io.promptfuzzer.execution.RunIssue;
import io.promptfuzzer.execution.TestResult;
import io.promptfuzzer.llm.ResilientChatModel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** Turns raw results into per-target summaries and a failure analysis. Pure and deterministic. */
public final class ReportBuilder {

    private static final int EXAMPLES_PER_CLUSTER = 6;

    private ReportBuilder() {
    }

    public static List<ModelSummary> summarize(List<String> models, List<TestResult> results) {
        List<ModelSummary> out = new ArrayList<>();
        for (String model : models) {
            out.add(summarize(model, results.stream().filter(r -> r.model().equals(model)).toList()));
        }
        return out;
    }

    static ModelSummary summarize(String model, List<TestResult> results) {
        int passed = count(results, Evaluation.Verdict.PASS);
        int failed = count(results, Evaluation.Verdict.FAIL);
        int errors = count(results, Evaluation.Verdict.ERROR);
        int skipped = count(results, Evaluation.Verdict.SKIPPED);
        double[] interval = passed + failed == 0 ? null : wilson(passed, passed + failed);

        int verified = 0;
        int judged = 0;
        int heuristic = 0;
        for (TestResult r : results) {
            if (r.verdict() == Evaluation.Verdict.PASS) {
                switch (r.evaluation().verification()) {
                    case DETERMINISTIC -> verified++;
                    case AI_JUDGE -> judged++;
                    case HEURISTIC -> heuristic++;
                }
            }
        }

        Map<TestCategory, ModelSummary.CategoryStats> byCategory = new EnumMap<>(TestCategory.class);
        for (TestCategory category : TestCategory.values()) {
            List<TestResult> inCategory = results.stream().filter(r -> r.category() == category).toList();
            if (!inCategory.isEmpty()) {
                int p = count(inCategory, Evaluation.Verdict.PASS);
                int f = count(inCategory, Evaluation.Verdict.FAIL);
                byCategory.put(category, new ModelSummary.CategoryStats(inCategory.size(), p, f,
                        count(inCategory, Evaluation.Verdict.ERROR), count(inCategory, Evaluation.Verdict.SKIPPED),
                        rate(p, p + f)));
            }
        }

        Map<FailureType, Integer> byFailure = new EnumMap<>(FailureType.class);
        results.stream().map(r -> r.evaluation().failureType()).filter(Objects::nonNull)
                .forEach(t -> byFailure.merge(t, 1, Integer::sum));

        Map<RunIssue, Integer> issues = new EnumMap<>(RunIssue.class);
        results.stream().map(TestResult::issue).filter(Objects::nonNull).forEach(i -> issues.merge(i, 1, Integer::sum));

        // Every response that arrived has a latency, including ones the judge could not grade.
        List<Long> latencies = results.stream()
                .filter(r -> r.rawOutput() != null)
                .map(TestResult::latencyMs).sorted().toList();
        ModelSummary.Latency latency = latencies.isEmpty() ? new ModelSummary.Latency(0, 0, 0, 0)
                : new ModelSummary.Latency(
                Math.round(latencies.stream().mapToLong(Long::longValue).average().orElse(0)),
                percentile(latencies, 0.50), percentile(latencies, 0.95), latencies.get(latencies.size() - 1));

        long in = results.stream().mapToLong(TestResult::inputTokens).sum();
        long outTokens = results.stream().mapToLong(TestResult::outputTokens).sum();
        boolean priced = results.stream().anyMatch(r -> r.costUsd() != null);
        Double cost = priced ? results.stream().map(TestResult::costUsd).filter(Objects::nonNull)
                .mapToDouble(Double::doubleValue).sum() : null;

        return new ModelSummary(model, results.size(), passed, failed, errors, skipped, rate(passed, passed + failed),
                interval == null ? null : interval[0], interval == null ? null : interval[1],
                verified, judged, heuristic, byCategory, byFailure, latency, in, outTokens, cost, consistency(results),
                issues);
    }

    /**
     * Share of tests (with at least two successful repeats) for which every repeat gave the same verdict and
     * the same parsed output. Null when nothing was repeated.
     */
    static Double consistency(List<TestResult> results) {
        Map<String, List<TestResult>> byTest = results.stream()
                .filter(r -> r.verdict().graded())
                .collect(Collectors.groupingBy(r -> r.model() + "\u0000" + r.testId(), LinkedHashMap::new, Collectors.toList()));
        int eligible = 0;
        int consistent = 0;
        for (List<TestResult> group : byTest.values()) {
            if (group.size() < 2) {
                continue;
            }
            eligible++;
            TestResult first = group.get(0);
            boolean same = group.stream().allMatch(r -> r.verdict() == first.verdict()
                    && Objects.equals(fingerprint(r), fingerprint(first)));
            if (same) {
                consistent++;
            }
        }
        return eligible == 0 ? null : (double) consistent / eligible;
    }

    private static String fingerprint(TestResult r) {
        return r.evaluation().parsed() != null ? Json.write(r.evaluation().parsed()) : r.rawOutput();
    }

    public static FailureAnalysis analyze(List<TestResult> results) {
        List<TestResult> failures = results.stream().filter(r -> r.verdict() == Evaluation.Verdict.FAIL).toList();
        Map<FailureType, List<TestResult>> grouped = new EnumMap<>(FailureType.class);
        for (TestResult r : failures) {
            grouped.computeIfAbsent(r.evaluation().failureType(), k -> new ArrayList<>()).add(r);
        }
        List<FailureAnalysis.Cluster> clusters = new ArrayList<>();
        grouped.forEach((type, list) -> {
            Map<String, Integer> byModel = new LinkedHashMap<>();
            Map<String, Integer> categories = new LinkedHashMap<>();
            for (TestResult r : list) {
                byModel.merge(r.model(), 1, Integer::sum);
                categories.merge(r.category().displayName(), 1, Integer::sum);
            }
            List<String> examples = pickExamples(list);
            clusters.add(new FailureAnalysis.Cluster(type, type.label(), type.description(), list.size(),
                    (double) list.size() / failures.size(), byModel, sortByValue(categories), examples,
                    Suggestions.forType(type)));
        });
        clusters.sort(Comparator.comparingInt(FailureAnalysis.Cluster::count).reversed()
                .thenComparing(FailureAnalysis.Cluster::type));
        return new FailureAnalysis(outcome(results), clusters, issueGroups(results), null);
    }

    static FailureAnalysis.Outcome outcome(List<TestResult> results) {
        int graded = (int) results.stream().filter(r -> r.verdict().graded()).count();
        if (graded == 0) {
            return FailureAnalysis.Outcome.NOTHING_GRADED;
        }
        if (count(results, Evaluation.Verdict.FAIL) > 0) {
            return FailureAnalysis.Outcome.FAILURES_FOUND;
        }
        return graded == results.size() ? FailureAnalysis.Outcome.ALL_PASSED : FailureAnalysis.Outcome.PASSED_WITH_ISSUES;
    }

    private static List<FailureAnalysis.IssueGroup> issueGroups(List<TestResult> results) {
        Map<RunIssue, List<TestResult>> grouped = new EnumMap<>(RunIssue.class);
        for (TestResult r : results) {
            if (!r.verdict().graded()) {
                grouped.computeIfAbsent(r.issue() != null ? r.issue() : RunIssue.PROVIDER_ERROR, k -> new ArrayList<>()).add(r);
            }
        }
        List<FailureAnalysis.IssueGroup> groups = new ArrayList<>();
        grouped.forEach((issue, list) -> {
            Map<String, Integer> byModel = new LinkedHashMap<>();
            list.forEach(r -> byModel.merge(r.model(), 1, Integer::sum));
            String first = list.stream().map(TestResult::error).filter(Objects::nonNull).findFirst()
                    .orElse(list.get(0).evaluation().summary());
            groups.add(new FailureAnalysis.IssueGroup(issue, issue.label(), issue.description(), issue.skipped(),
                    list.size(), byModel, pickExamples(list), first));
        });
        groups.sort(Comparator.comparingInt(FailureAnalysis.IssueGroup::count).reversed()
                .thenComparing(FailureAnalysis.IssueGroup::issue));
        return groups;
    }

    /**
     * Brings a report saved by an earlier version up to date. Those reports recorded skipped calls as errors
     * ("Skipped: ..."), had no error causes, stored 0% for pass rates that were never measured, and called a run
     * "completed" even when nothing was graded. The raw results are kept; only their classification and the
     * derived summaries are recomputed. Current reports are returned unchanged.
     */
    public static RunReport upgradeLegacy(RunReport report) {
        if (report.demo() || report.results() == null || report.results().isEmpty()
                || (report.analysis() != null && report.analysis().outcome() != null)) {
            return report;
        }
        List<TestResult> results = report.results().stream().map(ReportBuilder::classifyLegacy).toList();
        FailureAnalysis analysis = analyze(results);
        if (report.analysis() != null && report.analysis().aiInsights() != null) {
            analysis = analysis.withAiInsights(report.analysis().aiInsights());
        }
        RunReport.Status status = report.status() == RunReport.Status.COMPLETED
                ? completionStatus(results, false) : report.status();
        String message = report.status() == RunReport.Status.FAILED ? report.message()
                : issueSummary(results, report.settings(), Double.NaN);
        return new RunReport(report.id(), report.suiteId(), report.suiteName(), status, report.startedAt(),
                report.finishedAt(), report.settings(), report.completed(), report.total(),
                summarize(report.settings().targets(), results), results, analysis, report.judge(), report.suite(),
                message, false);
    }

    private static TestResult classifyLegacy(TestResult r) {
        if (r.issue() != null || r.verdict().graded()) {
            return r;
        }
        String error = r.error() == null ? "" : r.error();
        if (error.startsWith("Skipped") || error.startsWith("Run cancelled")) {
            RunIssue issue = error.contains("Cost limit") ? RunIssue.COST_LIMIT
                    : error.contains("Request limit") ? RunIssue.REQUEST_LIMIT : RunIssue.CANCELLED;
            return copy(r, Evaluation.skipped("Not sent: " + error.replaceFirst("^Skipped: ", "")), issue);
        }
        RunIssue issue = r.rawOutput() != null ? RunIssue.EVALUATION_ERROR
                : error.contains("timed out") ? RunIssue.TIMEOUT
                : error.contains("Network error") || error.contains("Cannot connect") ? RunIssue.CONNECTION_FAILED
                : RunIssue.PROVIDER_ERROR;
        return copy(r, r.evaluation(), issue);
    }

    private static TestResult copy(TestResult r, Evaluation evaluation, RunIssue issue) {
        return new TestResult(r.id(), r.testId(), r.model(), r.repeat(), r.category(), r.input(), r.expectedBehavior(),
                r.expectedOutput(), r.expectationSource(), r.rawOutput(), evaluation, r.latencyMs(), r.inputTokens(),
                r.outputTokens(), r.costUsd(), r.stopReason(), r.error(), issue);
    }

    /**
     * How a finished run ended. A run that finished but had call errors or skipped calls is not reported as a
     * plain success, whatever the model's pass rate.
     */
    public static RunReport.Status completionStatus(List<TestResult> results, boolean cancelled) {
        if (cancelled) {
            return RunReport.Status.CANCELLED;
        }
        boolean issues = results.isEmpty() || results.stream().anyMatch(r -> !r.verdict().graded());
        return issues ? RunReport.Status.COMPLETED_WITH_ERRORS : RunReport.Status.COMPLETED;
    }

    /**
     * A plain-language account of what kept results from being graded, naming the limit that actually stopped
     * calls: a request ceiling is never reported as money spent. Null when every result was graded.
     *
     * @param spentUsd what the run spent, or NaN when unknown
     */
    public static String issueSummary(List<TestResult> results, RunReport.RunSettings settings, double spentUsd) {
        List<FailureAnalysis.IssueGroup> groups = issueGroups(results);
        if (groups.isEmpty()) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        if (results.stream().noneMatch(r -> r.verdict().graded())) {
            parts.add("No response could be graded, so no pass rate was measured.");
        }
        for (FailureAnalysis.IssueGroup g : groups) {
            String calls = g.count() + (g.count() == 1 ? " call" : " calls");
            parts.add(switch (g.issue()) {
                case REQUEST_LIMIT -> calls + " not sent: the request limit of " + settings.maxRequests()
                        + " requests for this run was reached (retries count as requests). Raise PF_MAX_REQUESTS or run fewer tests.";
                case COST_LIMIT -> Double.isNaN(spentUsd)
                        ? String.format(Locale.ROOT, "%s not sent: the cost limit of $%.2f was reached.", calls, settings.maxCostUsd())
                        : String.format(Locale.ROOT, "%s not sent: the cost limit of $%.2f was reached (spent $%.4f).",
                                calls, settings.maxCostUsd(), spentUsd);
                case ENDPOINT_UNAVAILABLE -> calls + " not sent: PromptFuzzer stopped calling the endpoint after "
                        + ResilientChatModel.CONNECTION_FAILURE_LIMIT + " consecutive connection failures.";
                case CONNECTION_FAILED, TIMEOUT, PROVIDER_ERROR, EVALUATION_ERROR ->
                        calls + " failed (" + g.label().toLowerCase(Locale.ROOT) + ").";
                case CANCELLED -> calls + " not sent because the run was cancelled.";
            });
        }
        // The specific error, once: it names the cause and the fix. An unavailable endpoint repeats its last
        // connection error, so it is only quoted when no call error is reported alongside it.
        List<String> details = groups.stream().filter(g -> !g.skipped()).map(FailureAnalysis.IssueGroup::firstMessage)
                .filter(Objects::nonNull).distinct().limit(2).collect(Collectors.toCollection(ArrayList::new));
        if (details.isEmpty()) {
            groups.stream().filter(g -> g.issue() == RunIssue.ENDPOINT_UNAVAILABLE).findFirst()
                    .map(FailureAnalysis.IssueGroup::firstMessage).ifPresent(details::add);
        }
        details.forEach(d -> parts.add("Error: " + d));
        return String.join(" ", parts);
    }

    /** Prefers distinct test cases (and repeat 0) so examples are varied. */
    private static List<String> pickExamples(List<TestResult> list) {
        Map<String, TestResult> perTest = new LinkedHashMap<>();
        for (TestResult r : list) {
            perTest.putIfAbsent(r.testId(), r);
        }
        return perTest.values().stream().limit(EXAMPLES_PER_CLUSTER).map(TestResult::id).toList();
    }

    private static Map<String, Integer> sortByValue(Map<String, Integer> map) {
        return map.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new));
    }

    private static int count(List<TestResult> results, Evaluation.Verdict verdict) {
        return (int) results.stream().filter(r -> r.verdict() == verdict).count();
    }

    /** Null when nothing was graded: "not measured" is not the same as 0%. */
    private static Double rate(int passed, int n) {
        return n == 0 ? null : (double) passed / n;
    }

    static long percentile(List<Long> sorted, double p) {
        int index = (int) Math.ceil(p * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
    }

    /** 95% Wilson score interval: honest error bars for pass rates measured on small samples. */
    static double[] wilson(int successes, int n) {
        if (n == 0) {
            return new double[]{0, 0};
        }
        double z = 1.96;
        double p = (double) successes / n;
        double denom = 1 + z * z / n;
        double centre = p + z * z / (2 * n);
        double margin = z * Math.sqrt(p * (1 - p) / n + z * z / (4.0 * n * n));
        return new double[]{Math.max(0, (centre - margin) / denom), Math.min(1, (centre + margin) / denom)};
    }

    /** Index for looking results up by id. */
    public static Map<String, TestResult> byId(List<TestResult> results) {
        Map<String, TestResult> map = new HashMap<>();
        results.forEach(r -> map.put(r.id(), r));
        return map;
    }
}
