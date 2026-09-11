package com.jobcopilot.intelligence.evaluation;

import java.util.List;
import org.junit.jupiter.api.Test;
import static com.jobcopilot.intelligence.evaluation.EvaluationResult.*;
import static org.assertj.core.api.Assertions.assertThat;

class EvidenceProvenanceTest {
    @Test
    void comparesSourceAndOccurrenceAnchorsBySpanOverlap() {
        LoadedEvaluation loaded = new EvaluationDatasetLoader().load();
        EvaluationCase original = loaded.dataset().cases().stream()
                .filter(value -> value.id().equals("01-required-skill")).findFirst().orElseThrow();
        EvaluationCaseExecution raw = EvaluationRunnerTest.coordinator(
                new FixtureBackedModelSource(loaded.responses())).execute(original);
        GoldSkill moved = new GoldSkill("Java", com.jobcopilot.intelligence.JobIntelligence.Importance.REQUIRED,
                List.of(new EvidenceAnchor(com.jobcopilot.intelligence.JobIntelligence.Source.TITLE,
                        "Backend Engineer", 1)));
        EvaluationCase altered = new EvaluationCase(original.id(), original.tags(), original.reviewStatus(),
                original.title(), original.description(), original.canonicalRequirements(),
                new GoldTruth(List.of(moved), List.of(), List.of(), original.gold().minimumExperience()));
        EvaluationCaseExecution alteredRaw = new EvaluationCaseExecution(altered, raw.baseline(), raw.hybrid(), raw.llmFirst());

        EvaluatedCase compared = new SemanticComparator(EvaluationDatasetLoader.DATASET_VERSION,
                EvaluationDatasetLoader.RESPONSE_VERSION).evaluate(alteredRaw);

        assertThat(compared.hybrid().semantic().skills().evidenceAnchorMismatches()).isEqualTo(1);
        assertThat(compared.llmFirst().semantic().skills().evidenceAnchorMismatches()).isEqualTo(1);
    }
}
