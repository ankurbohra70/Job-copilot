package com.jobcopilot.intelligence.evaluation;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class EvaluationReportRendererTest {
    @Test
    void rendersStableSafeReportsWithUndefinedMetrics() {
        EvaluationReportRenderer renderer = new EvaluationReportRenderer();
        EvaluationResult.RenderedReport first = renderer.render(EvaluationRunnerTest.evaluateFixtureBacked());
        EvaluationResult.RenderedReport second = renderer.render(EvaluationRunnerTest.evaluateFixtureBacked());

        assertThat(first).isEqualTo(second);
        assertThat(first.text()).contains("mode=FIXTURE_BACKED", "23/24 LLM_FIRST cases reuse the HYBRID candidate payload",
                "Req Qual F1", "Pref Qual F1", "Evidence-anchor mismatches", "—", "Failure breakdown",
                "Category breakdown", "Per-case disagreements")
                .doesNotContain("timestamp", "secret-message", "Authorization", "Bearer ");
        assertThat(first.json()).contains("\"mode\" : \"FIXTURE_BACKED\"",
                "\"reusedHybridCandidatePayloads\" : 23", ": null")
                .doesNotContain("secret-message", "provider body", "OPENAI_API_KEY");
        java.util.List<String> categoryTags = java.util.Arrays.stream(first.text().split("\n"))
                .dropWhile(line -> !line.equals("Category breakdown"))
                .skip(1)
                .takeWhile(line -> !line.isBlank() && !line.equals("Per-case disagreements"))
                .map(line -> line.substring(0, line.indexOf(':')))
                .toList();
        assertThat(categoryTags).isNotEmpty().isSorted();
    }
}
