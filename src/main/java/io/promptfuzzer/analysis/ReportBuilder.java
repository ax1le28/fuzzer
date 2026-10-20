package io.promptfuzzer.analysis;

import io.promptfuzzer.core.Json;
import io.promptfuzzer.core.TestCategory;
import io.promptfuzzer.evaluation.Evaluation;
import io.promptfuzzer.evaluation.FailureType;
import io.promptfuzzer.execution.TestResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
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
        double[] interval = wilson(passed, passed + failed);

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
                        count(inCategory, Evaluation.Verdict.ERROR), rate(p, p + f)));
            }
        }

        Map<FailureType, Integer> byFailure = new EnumMap<>(FailureType.class);
        results.stream().map(r -> r.evaluation().failureType()).filter(Objects::nonNull)
                .forEach(t -> byFailure.merge(t, 1, Integer::sum));

        List<Long> latencies = results.stream()
                .filter(r -> r.verdict() != Evaluation.Verdict.ERROR || r.latencyMs() > 0)
                .filter(r -> r.error() == null)
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

        return new ModelSummary(model, results.size(), passed, failed, errors, rate(passed, passed + failed),
                interval[0], interval[1], verified, judged, heuristic, byCategory, byFailure, latency,
                in, outTokens, cost, consistency(results));
    }

    /**
     * Share of tests (with at least two successful repeats) for which every repeat gave the same verdict and
     * the same parsed output. Null when nothing was repeated.
     */
    static Double consistency(List<TestResult> results) {
        Map<String, List<TestResult>> byTest = results.stream()
                .filter(r -> r.verdict() != Evaluation.Verdict.ERROR)
                .collect(Collectors.groupingBy(TestResult::testId, LinkedHashMap::new, Collectors.toList()));
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
        return new FailureAnalysis(clusters, null);
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

    private static double rate(int passed, int n) {
        return n == 0 ? 0 : (double) passed / n;
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
