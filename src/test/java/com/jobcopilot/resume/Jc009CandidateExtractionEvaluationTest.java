package com.jobcopilot.resume;

import com.jobcopilot.evaluation.DeterministicEvaluationMetrics;
import com.jobcopilot.evaluation.Jc009GoldenDataset;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class Jc009CandidateExtractionEvaluationTest {
    @Test
    void humanLabelsMeasureStructuredExtractionWithoutRuntimeInfluence() {
        Jc009GoldenDataset dataset = Jc009GoldenDataset.load();
        CandidateProfileExtractor extractor = new DeterministicCandidateProfileExtractor(
                new DeterministicProfileParser());
        List<String> expectedSets = new ArrayList<>();
        List<String> actualSets = new ArrayList<>();
        long exactFields = 0;
        long fieldCount = 0;
        long experienceCorrect = 0;
        long unknownStateCorrect = 0;
        long dateCorrect = 0;
        long identityCorrect = 0;

        for (JsonNode evaluationCase : dataset.cases("candidateExtraction")) {
            String id = evaluationCase.path("id").asText();
            CandidateProfileExtraction extraction = extractor.extract(evaluationCase.path("text").asText(),
                    LocalDate.parse(evaluationCase.path("assessedOn").asText()));
            CandidateProfileData actual = extraction.profile();
            JsonNode gold = evaluationCase.path("gold");
            assertEquals(dataset.text("candidateExtractorVersion"), extraction.extractorVersion());
            assertEquals(dataset.text("matchingVocabularyVersion"), extraction.vocabularyVersion());

            for (String field : List.of("skills", "keywords", "roleCategories")) {
                List<String> expected = Jc009GoldenDataset.strings(gold, field);
                List<String> observed = switch (field) {
                    case "skills" -> actual.skills();
                    case "keywords" -> actual.keywords();
                    default -> actual.roleCategories();
                };
                expected.forEach(value -> expectedSets.add(id + ":" + field + ":" + value));
                observed.forEach(value -> actualSets.add(id + ":" + field + ":" + value));
                if (expected.equals(observed)) exactFields++;
                fieldCount++;
            }
            Integer expectedMonths = gold.path("totalExperienceMonths").isNull()
                    ? null : gold.path("totalExperienceMonths").asInt();
            if (java.util.Objects.equals(expectedMonths, actual.totalExperienceMonths())) experienceCorrect++;
            if (gold.path("experienceAssessment").asText().equals(actual.experienceAssessment().name()))
                unknownStateCorrect++;
            CandidateProfileData.WorkExperience first = actual.workExperience().getFirst();
            if (java.util.Objects.equals(nullableText(gold.path("firstExperienceStart")),
                    format(first.start()))) dateCorrect++;
            if (java.util.Objects.equals(nullableText(gold.path("firstExperienceEnd")),
                    format(first.end()))) dateCorrect++;
            // CandidateProfileExtraction currently has no identity-facts output, so the deterministic
            // baseline explicitly scores those predictions as absent instead of inventing them.
            if (java.util.Objects.equals(nullableText(gold.path("selectedIdentity").path("fullName")), null))
                identityCorrect++;
            if (java.util.Objects.equals(nullableText(gold.path("selectedIdentity").path("email")), null))
                identityCorrect++;
            assertEquals(Jc009GoldenDataset.strings(gold, "warnings"), actual.warnings());
        }

        var setMetrics = DeterministicEvaluationMetrics.sets(expectedSets, actualSets);
        assertEquals(1.0, setMetrics.precision());
        assertEquals(1.0, setMetrics.recall());
        assertEquals(1.0, setMetrics.f1());
        assertEquals(1.0, DeterministicEvaluationMetrics.accuracy(exactFields, fieldCount));
        assertEquals(1.0, DeterministicEvaluationMetrics.accuracy(
                experienceCorrect, dataset.cases("candidateExtraction").size()));
        assertEquals(1.0, DeterministicEvaluationMetrics.accuracy(
                unknownStateCorrect, dataset.cases("candidateExtraction").size()));
        assertEquals(1.0, DeterministicEvaluationMetrics.accuracy(
                dateCorrect, dataset.cases("candidateExtraction").size() * 2L));
        assertEquals(0.5, DeterministicEvaluationMetrics.accuracy(
                identityCorrect, dataset.cases("candidateExtraction").size() * 2L));
    }

    private static String format(CandidateProfileData.PartialDate value) {
        if (value == null) return null;
        return value.month() == null ? Integer.toString(value.year())
                : "%04d-%02d".formatted(value.year(), value.month());
    }

    private static String nullableText(JsonNode node) {
        return node.isNull() ? null : node.asText();
    }
}
