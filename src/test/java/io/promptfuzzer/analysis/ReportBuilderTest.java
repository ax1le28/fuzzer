package io.promptfuzzer.analysis;

import io.promptfuzzer.app.DemoBuilder;
import io.promptfuzzer.evaluation.Evaluation;
import io.promptfuzzer.evaluation.FailureType;
import io.promptfuzzer.execution.TestResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportBuilderTest {

    @Test
    void wilsonIntervalMatchesKnownValues() {
        double[] ci = ReportBuilder.wilson(8, 10);
        assertEquals(0.490, ci[0], 0.001);
        assertEquals(0.943, ci[1], 0.001);
        double[] all = ReportBuilder.wilson(10, 10);
        assertEquals(1.0, all[1], 1e-9);
        assertTrue(all[0] < 1.0, "a perfect score on 10 tests is not certainty");
        assertEquals(0, ReportBuilder.wilson(0, 0)[1]);
    }

    @Test
    void percentiles() {
        List<Long> v = List.of(10L, 20L, 30L, 40L, 50L, 60L, 70L, 80L, 90L, 100L);
        assertEquals(50, ReportBuilder.percentile(v, 0.5));
        assertEquals(100, ReportBuilder.percentile(v, 0.95));
        assertEquals(10, ReportBuilder.percentile(List.of(10L), 0.95));
    }

    /** The demo runs the real pipeline end to end, so it doubles as an integration test of the report math. */
    @Test
    void demoReportIsInternallyConsistent() {
        RunReport report = DemoBuilder.build();
        assertTrue(report.demo());
        assertEquals(2, report.models().size());
        for (ModelSummary m : report.models()) {
            List<TestResult> mine = report.results().stream().filter(r -> r.model().equals(m.model())).toList();
            assertEquals(mine.size(), m.total());
            assertEquals(m.total(), m.passed() + m.failed() + m.errors());
            assertEquals(m.passed(), m.verifiedPasses() + m.judgedPasses() + m.heuristicPasses());
            assertTrue(m.passRateLow() <= m.passRate() && m.passRate() <= m.passRateHigh());
            assertNotNull(m.consistency(), "demo uses two repeats");
            int byCategory = m.byCategory().values().stream().mapToInt(ModelSummary.CategoryStats::total).sum();
            assertEquals(m.total(), byCategory);
            int byFailure = m.byFailureType().values().stream().mapToInt(Integer::intValue).sum();
            assertEquals(m.failed(), byFailure);
        }
        ModelSummary large = report.models().get(0);
        ModelSummary small = report.models().get(1);
        assertTrue(large.passRate() > small.passRate());
        assertTrue(large.consistency() < 1.0, "a recorded second answer differs");

        int clustered = report.analysis().clusters().stream().mapToInt(FailureAnalysis.Cluster::count).sum();
        long failures = report.results().stream().filter(r -> r.verdict() == Evaluation.Verdict.FAIL).count();
        assertEquals(failures, clustered);
        assertNull(report.analysis().aiInsights(), "demo never pretends to have AI insights");

        Set<FailureType> seen = report.analysis().clusters().stream().map(FailureAnalysis.Cluster::type).collect(Collectors.toSet());
        assertTrue(seen.containsAll(Set.of(FailureType.FORMATTING, FailureType.NEGATION_MISUNDERSTANDING,
                FailureType.PROMPT_INJECTION, FailureType.AMBIGUITY_HANDLING, FailureType.INVALID_STRUCTURE,
                FailureType.HALLUCINATED_INFORMATION, FailureType.MISSING_INFORMATION)), seen.toString());
        report.analysis().clusters().forEach(c -> assertTrue(c.suggestion().length() > 20));
    }

    @Test
    void consistencyIsNullWithoutRepeats() {
        RunReport report = DemoBuilder.build();
        List<TestResult> firstOnly = report.results().stream().filter(r -> r.repeat() == 0).toList();
        assertNull(ReportBuilder.consistency(firstOnly));
    }
}
