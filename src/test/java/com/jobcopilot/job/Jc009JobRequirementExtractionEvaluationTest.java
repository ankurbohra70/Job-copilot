package com.jobcopilot.job;

import com.jobcopilot.evaluation.DeterministicEvaluationMetrics;
import com.jobcopilot.evaluation.Jc009GoldenDataset;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class Jc009JobRequirementExtractionEvaluationTest {
    @Test
    void humanLabelsMeasureRequirementsExperienceFalsePositivesAndAbstention() {
        Jc009GoldenDataset dataset = Jc009GoldenDataset.load();
        JobRequirementExtractor extractor = new JobRequirementExtractor();
        List<String> expectedSkills = new ArrayList<>();
        List<String> actualSkills = new ArrayList<>();
        List<Boolean> expectedPositive = new ArrayList<>();
        List<Boolean> predictedPositive = new ArrayList<>();
        List<Boolean> abstained = new ArrayList<>();
        long exactExperience = 0;
        long withinToleranceExperience = 0;
        long evaluatedExperience = 0;

        assertEquals(dataset.text("jobRequirementExtractorVersion"), extractor.version());
        for (JsonNode evaluationCase : dataset.cases("jobRequirementExtraction")) {
            String id = evaluationCase.path("id").asText();
            JsonNode gold = evaluationCase.path("gold");
            boolean expectedAbstention = gold.path("abstain").asBoolean();
            JobRequirementExtractor.Extraction actual = null;
            try {
                actual = extractor.extract(evaluationCase.path("description").asText());
            } catch (JobRequirementExtractionException expected) {
                // Abstention is an explicit, scored output for unextractable descriptions.
            }
            boolean didAbstain = actual == null;
            expectedPositive.add(!expectedAbstention);
            predictedPositive.add(!didAbstain);
            abstained.add(didAbstain);

            List<String> required = Jc009GoldenDataset.strings(gold, "requiredSkills");
            List<String> preferred = Jc009GoldenDataset.strings(gold, "preferredSkills");
            required.forEach(value -> expectedSkills.add(id + ":required:" + value));
            preferred.forEach(value -> expectedSkills.add(id + ":preferred:" + value));
            if (actual != null) {
                actual.requiredSkills().forEach(value -> actualSkills.add(id + ":required:" + value));
                actual.preferredSkills().forEach(value -> actualSkills.add(id + ":preferred:" + value));
                BigDecimal expected = gold.path("minimumYearsExperience").isNull() ? null
                        : gold.path("minimumYearsExperience").decimalValue();
                if (java.util.Objects.equals(expected, actual.minYearsExperience())) exactExperience++;
                if (expected == null ? actual.minYearsExperience() == null
                        : actual.minYearsExperience() != null
                        && expected.subtract(actual.minYearsExperience()).abs()
                                .compareTo(new BigDecimal("0.25")) <= 0) withinToleranceExperience++;
                evaluatedExperience++;
            }
            assertEquals(expectedAbstention, didAbstain, id);
        }

        var skillMetrics = DeterministicEvaluationMetrics.sets(expectedSkills, actualSkills);
        assertEquals(1.0, skillMetrics.precision());
        assertEquals(1.0, skillMetrics.recall());
        assertEquals(1.0, skillMetrics.f1());
        assertEquals(1.0, DeterministicEvaluationMetrics.accuracy(exactExperience, evaluatedExperience));
        assertEquals(1.0, DeterministicEvaluationMetrics.accuracy(
                withinToleranceExperience, evaluatedExperience));
        var rates = DeterministicEvaluationMetrics.binaryRates(expectedPositive, predictedPositive, abstained);
        assertEquals(0.0, rates.falsePositiveRate());
        assertEquals(0.333333, rates.abstentionRate());
    }
}
