package com.jobcopilot.application;

import com.jobcopilot.discovery.ExtractionState;
import com.jobcopilot.discovery.JobVerificationService;
import com.jobcopilot.discovery.JobVerificationSnapshot;
import com.jobcopilot.discovery.ListingAvailability;
import com.jobcopilot.evaluation.DeterministicEvaluationMetrics;
import com.jobcopilot.evaluation.Jc009GoldenDataset;
import com.jobcopilot.job.JobApplicationSnapshot;
import com.jobcopilot.job.JobMatchingSnapshot;
import com.jobcopilot.job.JobService;
import com.jobcopilot.job.JobStatus;
import com.jobcopilot.matching.JobAssessmentService;
import com.jobcopilot.matching.MatchResult;
import com.jobcopilot.matching.dto.MatchResponse;
import com.jobcopilot.resume.CandidateFactState;
import com.jobcopilot.resume.CandidateProfileFacts;
import com.jobcopilot.resume.JobSearchPreferenceData;
import com.jobcopilot.resume.JobSearchPreferenceService;
import com.jobcopilot.resume.ResumePersistenceService;
import com.jobcopilot.resume.ResumeRouteService;
import com.jobcopilot.resume.ResumeRouteSnapshot;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class Jc009DecisionReadinessEvaluationTest {
    @Test
    void humanLabelsMeasureDecisionsReadinessConfusionAndSafetyErrors() {
        Jc009GoldenDataset dataset = Jc009GoldenDataset.load();
        ApplicationDecisionService decisionService = new ApplicationDecisionService(
                mock(JobAssessmentService.class), mock(ResumePersistenceService.class),
                mock(JobSearchPreferenceService.class), mock(JobService.class));
        ApplicationReadinessService readinessService = new ApplicationReadinessService(
                mock(JobService.class), mock(JobVerificationService.class), mock(JobAssessmentService.class),
                decisionService, mock(ResumePersistenceService.class), mock(JobSearchPreferenceService.class),
                mock(ResumeRouteService.class));
        List<String> expectedDecisions = new ArrayList<>();
        List<String> actualDecisions = new ArrayList<>();
        List<String> expectedReadiness = new ArrayList<>();
        List<String> actualReadiness = new ArrayList<>();
        List<Boolean> applyWorthy = new ArrayList<>();
        List<Boolean> doNotApply = new ArrayList<>();
        long materialCheckCorrect = 0;
        long materialCheckCount = 0;
        long jobId = 100;

        assertEquals(dataset.text("decisionPolicyVersion"), ApplicationDecisionPolicy.VERSION);
        for (JsonNode evaluationCase : dataset.cases("decisionReadiness")) {
            long currentJobId = jobId++;
            MatchResponse match = match(currentJobId, evaluationCase);
            CandidateProfileFacts facts = facts(evaluationCase);
            JobSearchPreferenceData preference = preference(evaluationCase);
            String title = "Backend Engineer";
            ApplicationDecisionResult decision = decisionService.decide(currentJobId, 1L, match,
                    facts, preference, title);
            JobApplicationSnapshot job = new JobApplicationSnapshot(new JobMatchingSnapshot(currentJobId,
                    title, "Payments", "Bengaluru", null, LocalDateTime.of(2026, 9, 15, 12, 0)),
                    "Company", null, JobStatus.DISCOVERED);
            JobVerificationSnapshot verification = new JobVerificationSnapshot(currentJobId,
                    ListingAvailability.LIVE, "https://jobs.example.test/" + currentJobId,
                    "https://apply.example.test/" + currentJobId, ExtractionState.CURRENT,
                    LocalDateTime.now());
            ResumeRouteSnapshot route = evaluationCase.path("routePresent").asBoolean()
                    ? route(currentJobId, decision.recommendedResumeStrategy()) : null;
            ApplicationReadinessResult readiness = readinessService.evaluate(job, 1L, match, decision,
                    facts, preference, verification, route);
            JsonNode gold = evaluationCase.path("gold");
            expectedDecisions.add(gold.path("decision").asText());
            actualDecisions.add(decision.decision().name());
            expectedReadiness.add(gold.path("readiness").asText());
            actualReadiness.add(readiness.readiness().name());
            applyWorthy.add(gold.path("applyWorthy").asBoolean());
            doNotApply.add(gold.path("doNotApply").asBoolean());
            Map<String, ReadinessCheck.Status> actualChecks = readiness.checks().stream()
                    .collect(java.util.stream.Collectors.toMap(ReadinessCheck::code, ReadinessCheck::status));
            var fields = gold.path("checks").properties();
            for (var field : fields) {
                materialCheckCount++;
                if (field.getValue().asText().equals(actualChecks.get(field.getKey()).name()))
                    materialCheckCorrect++;
            }
        }

        assertEquals(1.0, DeterministicEvaluationMetrics.accuracy(
                equalCount(expectedDecisions, actualDecisions), expectedDecisions.size()));
        assertEquals(1.0, DeterministicEvaluationMetrics.accuracy(
                equalCount(expectedReadiness, actualReadiness), expectedReadiness.size()));
        assertEquals(1.0, DeterministicEvaluationMetrics.accuracy(materialCheckCorrect, materialCheckCount));
        assertEquals(diagonalCounts(expectedDecisions),
                DeterministicEvaluationMetrics.confusion(expectedDecisions, actualDecisions));
        assertEquals(diagonalCounts(expectedReadiness),
                DeterministicEvaluationMetrics.confusion(expectedReadiness, actualReadiness));
        long falseSkips = DeterministicEvaluationMetrics.falseSkipCount(actualDecisions, applyWorthy);
        long falseApplyDecisions = DeterministicEvaluationMetrics.falseApplyDecisionCount(actualDecisions, doNotApply);
        long falseApplyReadiness = DeterministicEvaluationMetrics.falseApplyReadinessCount(actualReadiness, doNotApply);
        assertEquals(0.0, DeterministicEvaluationMetrics.rate(falseSkips,
                applyWorthy.stream().filter(Boolean::booleanValue).count()));
        assertEquals(0.0, DeterministicEvaluationMetrics.rate(falseApplyDecisions,
                doNotApply.stream().filter(Boolean::booleanValue).count()));
        assertEquals(0.0, DeterministicEvaluationMetrics.rate(falseApplyReadiness,
                doNotApply.stream().filter(Boolean::booleanValue).count()));
    }

    private static MatchResponse match(long jobId, JsonNode value) {
        MatchResponse result = mock(MatchResponse.class);
        when(result.jobId()).thenReturn(jobId);
        when(result.overallScore()).thenReturn(value.path("score").decimalValue());
        when(result.recommendation()).thenReturn(MatchResult.Recommendation.valueOf(
                value.path("recommendation").asText()));
        when(result.experienceComparison()).thenReturn(new MatchResult.ExperienceComparison(
                BigDecimal.valueOf(3), BigDecimal.valueOf(5), MatchResult.Status.MEETS_REQUIREMENT,
                BigDecimal.valueOf(100)));
        when(result.missingRequiredSkills()).thenReturn(List.of());
        return result;
    }

    private static CandidateProfileFacts facts(JsonNode value) {
        boolean identity = value.path("identityPresent").asBoolean();
        return new CandidateProfileFacts(identity ? "Candidate" : null,
                identity ? "candidate@example.com" : null, null, "Bengaluru", "Backend Engineer", 60,
                CandidateFactState.valueOf(value.path("workAuthorization").asText()),
                CandidateFactState.valueOf(value.path("sponsorshipRequired").asText()), null);
    }

    private static JobSearchPreferenceData preference(JsonNode value) {
        if (!value.path("preferencePresent").asBoolean()) return null;
        return new JobSearchPreferenceData(
                value.path("defaultResumeStrategy").isNull() ? null
                        : ResumeStrategy.valueOf(value.path("defaultResumeStrategy").asText()),
                List.of("Backend Engineer"), Jc009GoldenDataset.strings(value, "excludedRoles"),
                List.of("Bengaluru"), Set.of(), null, null, 7);
    }

    private static ResumeRouteSnapshot route(long id, ResumeStrategy strategy) {
        return new ResumeRouteSnapshot(id, 1L, 1L, strategy, null, true,
                "Reviewed route", true, "resume.pdf", "pdfbox-v1", LocalDateTime.of(2026, 9, 15, 12, 0));
    }

    private static long equalCount(List<String> expected, List<String> actual) {
        long count = 0;
        for (int index = 0; index < expected.size(); index++)
            if (expected.get(index).equals(actual.get(index))) count++;
        return count;
    }

    private static Map<String, Map<String, Integer>> diagonalCounts(List<String> values) {
        Map<String, Map<String, Integer>> result = new java.util.LinkedHashMap<>();
        values.forEach(value -> result.computeIfAbsent(value, ignored -> new java.util.LinkedHashMap<>())
                .merge(value, 1, Integer::sum));
        return result;
    }
}
