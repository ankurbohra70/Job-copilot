package com.jobcopilot.evaluation;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class Jc009EvaluationFoundationTest {
    @Test
    void datasetIsVersionedReviewedCompleteAndReproduciblyOrdered() {
        Jc009GoldenDataset first = Jc009GoldenDataset.load();
        Jc009GoldenDataset second = Jc009GoldenDataset.load();

        assertEquals(Jc009GoldenDataset.VERSION, first.text("datasetVersion"));
        assertEquals("HUMAN_REVIEWED", first.text("labelAuthority"));
        for (String stage : List.of("candidateExtraction", "jobRequirementExtraction",
                "matchingRanking", "decisionReadiness")) {
            assertFalse(first.cases(stage).isEmpty());
            assertEquals(first.cases(stage).stream().map(value -> value.path("id").asText()).toList(),
                    second.cases(stage).stream().map(value -> value.path("id").asText()).toList());
        }
    }

    @Test
    void datasetRejectsNonHumanLabelsAndVersionDrift() throws Exception {
        String source;
        try (var input = getClass().getResourceAsStream("/jc009/evaluation/v1/golden-cases.json")) {
            assertNotNull(input);
            source = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        assertThrows(IllegalArgumentException.class, () -> Jc009GoldenDataset.parse(
                source.replace("HUMAN_REVIEWED", "MODEL_GENERATED")));
        assertThrows(IllegalArgumentException.class, () -> Jc009GoldenDataset.parse(
                source.replaceFirst("jc009-offline-evaluation-v1", "jc009-offline-evaluation-v2")));
        assertThrows(IllegalArgumentException.class, () -> Jc009GoldenDataset.parse(
                source.replaceFirst("\"reviewStatus\": \"REVIEWED\"", "\"reviewStatus\": \"DRAFT\"")));
    }

    @Test
    void deterministicMetricsCoverSetsRankingClassificationAndUndefinedDenominators() {
        var sets = DeterministicEvaluationMetrics.sets(List.of("java", "spring"),
                List.of("java", "python"));
        assertEquals(1, sets.truePositive());
        assertEquals(1, sets.falsePositive());
        assertEquals(1, sets.falseNegative());
        assertEquals(0.5, sets.precision());
        assertEquals(0.5, sets.recall());
        assertEquals(0.5, sets.f1());
        assertNull(DeterministicEvaluationMetrics.sets(List.of(), List.of()).precision());
        assertNull(DeterministicEvaluationMetrics.accuracy(0, 0));

        assertEquals(1.0, DeterministicEvaluationMetrics.precisionAtK(
                List.of("a", "b", "c"), Set.of("a", "b"), 2));
        assertEquals(1.0, DeterministicEvaluationMetrics.ndcgAtK(
                List.of("a", "b", "c"), Map.of("a", 3, "b", 2, "c", 0), 3));
        assertNull(DeterministicEvaluationMetrics.precisionAtK(List.of("a"), Set.of("a"), 2));

        assertEquals(Map.of("READY", Map.of("READY", 1), "NOT_READY", Map.of("NEEDS_USER", 1)),
                DeterministicEvaluationMetrics.confusion(
                        List.of("READY", "NOT_READY"), List.of("READY", "NEEDS_USER")));
        assertEquals(1, DeterministicEvaluationMetrics.falseSkipCount(
                List.of("SKIP", "SAVE"), List.of(true, true)));
        assertEquals(1, DeterministicEvaluationMetrics.falseApplyDecisionCount(
                List.of("APPLY_VOLUME", "SKIP"), List.of(true, true)));
        assertEquals(1, DeterministicEvaluationMetrics.falseApplyReadinessCount(
                List.of("READY", "NOT_READY"), List.of(true, true)));
    }
}
