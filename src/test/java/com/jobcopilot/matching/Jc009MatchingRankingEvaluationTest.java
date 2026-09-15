package com.jobcopilot.matching;

import com.jobcopilot.evaluation.DeterministicEvaluationMetrics;
import com.jobcopilot.evaluation.Jc009GoldenDataset;
import com.jobcopilot.job.JobMatchingSnapshot;
import com.jobcopilot.job.JobRankingSnapshot;
import com.jobcopilot.job.JobStatus;
import com.jobcopilot.job.dto.JobRequirementsResponse;
import com.jobcopilot.resume.CandidateMatchingSnapshot;
import com.jobcopilot.resume.CandidateProfileData;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class Jc009MatchingRankingEvaluationTest {
    @Test
    void humanLabelsGuardMatchParityBoundariesPrecisionAtKAndRankingQuality() {
        Jc009GoldenDataset dataset = Jc009GoldenDataset.load();
        JsonNode section = dataset.section("matchingRanking");
        JsonNode candidateGold = section.path("candidate");
        CandidateProfileData profile = new CandidateProfileData(
                Jc009GoldenDataset.strings(candidateGold, "skills"),
                candidateGold.path("totalExperienceMonths").asInt(),
                candidateGold.path("totalExperienceMonths").asInt(),
                CandidateProfileData.Assessment.KNOWN, List.of(), List.of(), List.of(),
                Jc009GoldenDataset.strings(candidateGold, "keywords"),
                Jc009GoldenDataset.strings(candidateGold, "roleCategories"), List.of(), List.of());
        CandidateMatchingSnapshot candidate = new CandidateMatchingSnapshot(1L, profile,
                dataset.text("candidateExtractorVersion"), dataset.text("matchingVocabularyVersion"),
                LocalDate.of(2026, 9, 15), 1);
        DeterministicMatchingEngine engine = new DeterministicMatchingEngine();
        assertEquals(dataset.text("matchingAlgorithmVersion"), engine.version());

        List<String> expectedFacts = new ArrayList<>();
        List<String> actualFacts = new ArrayList<>();
        List<Evaluated> evaluated = new ArrayList<>();
        long scoreParity = 0;
        long recommendationParity = 0;
        long boundaryErrors = 0;
        long id = 10;
        for (JsonNode evaluationCase : dataset.cases("matchingRanking")) {
            JobRankingSnapshot job = job(id++, evaluationCase);
            MatchResult result = engine.match(job.matching(), candidate);
            JsonNode gold = evaluationCase.path("gold");
            String caseId = evaluationCase.path("id").asText();
            for (String field : List.of("matchedRequiredSkills", "missingRequiredSkills")) {
                List<String> expected = Jc009GoldenDataset.strings(gold, field);
                List<String> actual = field.startsWith("matched")
                        ? result.matchedRequiredSkills() : result.missingRequiredSkills();
                expected.forEach(value -> expectedFacts.add(caseId + ":" + field + ":" + value));
                actual.forEach(value -> actualFacts.add(caseId + ":" + field + ":" + value));
            }
            if (gold.path("overallScore").decimalValue().compareTo(result.overallScore()) == 0) scoreParity++;
            String expectedRecommendation = gold.path("recommendation").asText();
            if (expectedRecommendation.equals(result.recommendation().name())) recommendationParity++;
            else boundaryErrors++;
            evaluated.add(new Evaluated(caseId, job, result, gold.path("relevanceGrade").asInt()));
        }

        evaluated.sort((left, right) -> JobRankingOrder.compare(
                left.job(), left.result(), right.job(), right.result()));
        List<String> actualRanking = evaluated.stream().map(Evaluated::caseId).toList();
        List<String> expectedRanking = Jc009GoldenDataset.strings(section, "goldRanking");
        Map<String, Integer> relevance = new LinkedHashMap<>();
        evaluated.forEach(value -> relevance.put(value.caseId(), value.relevanceGrade()));
        Set<String> relevant = relevance.entrySet().stream().filter(entry -> entry.getValue() > 0)
                .map(Map.Entry::getKey).collect(java.util.stream.Collectors.toSet());
        int k = section.path("precisionAtK").asInt();

        var facts = DeterministicEvaluationMetrics.sets(expectedFacts, actualFacts);
        assertEquals(1.0, facts.precision());
        assertEquals(1.0, facts.recall());
        assertEquals(1.0, facts.f1());
        assertEquals(1.0, DeterministicEvaluationMetrics.accuracy(scoreParity, evaluated.size()));
        assertEquals(1.0, DeterministicEvaluationMetrics.accuracy(recommendationParity, evaluated.size()));
        assertEquals(0, boundaryErrors);
        assertEquals(expectedRanking, actualRanking);
        assertEquals(1.0, DeterministicEvaluationMetrics.precisionAtK(actualRanking, relevant, k));
        assertEquals(1.0, DeterministicEvaluationMetrics.ndcgAtK(actualRanking, relevance, k));
    }

    private static JobRankingSnapshot job(long id, JsonNode value) {
        JobMatchingSnapshot matching = new JobMatchingSnapshot(id, value.path("title").asText(),
                value.path("description").asText(), "Bengaluru",
                new JobRequirementsResponse(Jc009GoldenDataset.strings(value, "requiredSkills"),
                        Jc009GoldenDataset.strings(value, "preferredSkills"),
                        value.path("minimumYearsExperience").decimalValue()),
                LocalDateTime.of(2026, 9, 15, 12, 0));
        return new JobRankingSnapshot(matching, "Company", null, JobStatus.DISCOVERED,
                LocalDateTime.of(2026, 9, 15, 12, 0).plusSeconds(id));
    }

    private record Evaluated(String caseId, JobRankingSnapshot job, MatchResult result, int relevanceGrade) {
    }
}
