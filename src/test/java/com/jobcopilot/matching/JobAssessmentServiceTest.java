package com.jobcopilot.matching;

import com.jobcopilot.common.text.MatchingVocabulary;
import com.jobcopilot.job.JobMatchingSnapshot;
import com.jobcopilot.job.JobNotFoundException;
import com.jobcopilot.job.JobService;
import com.jobcopilot.job.dto.JobRequirementsResponse;
import com.jobcopilot.matching.dto.MatchResponse;
import com.jobcopilot.resume.CandidateMatchingSnapshot;
import com.jobcopilot.resume.CandidateProfileData;
import com.jobcopilot.resume.DeterministicProfileParser;
import com.jobcopilot.resume.ResumeExceptions;
import com.jobcopilot.resume.ResumePersistenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InOrder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static com.jobcopilot.matching.MatchResult.Status;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class JobAssessmentServiceTest {
    private final JobService jobs = mock(JobService.class);
    private final ResumePersistenceService resumes = mock(ResumePersistenceService.class);
    private final DeterministicMatchingEngine engine = mock(DeterministicMatchingEngine.class);
    private final JobAssessmentService service = new JobAssessmentService(jobs, resumes, engine);
    private final DeterministicMatchingEngine realEngine = new DeterministicMatchingEngine();

    private JobMatchingSnapshot job;
    private CandidateMatchingSnapshot candidate;
    private MatchResult result;

    @BeforeEach
    void snapshots() {
        job = jobSnapshot("Backend Engineer", "Java payments systems", List.of("java", "spring-boot"), List.of("postgresql"), "3");
        candidate = candidateSnapshot("""
                Experience
                Backend Engineer at Acme
                2020-01 - 2023-01
                - Java Spring Boot services
                Skills
                Java, Spring Boot, PostgreSQL
                """, 36);
        result = realEngine.match(job, candidate);
        when(jobs.matchingSnapshot(1L)).thenReturn(job);
        when(resumes.matchingSnapshot(2L)).thenReturn(candidate);
        when(engine.match(job, candidate)).thenReturn(result);
        when(engine.version()).thenReturn(realEngine.version());
    }

    @Test
    void successfulAssessmentLoadsCompleteSnapshotsInvokesEngineOnceAndAssemblesMetadata() {
        MatchResponse response = service.assess(1L, 2L);

        verify(jobs).matchingSnapshot(1L);
        verify(resumes).matchingSnapshot(2L);
        verify(engine).match(job, candidate);
        InOrder order = inOrder(jobs, resumes, engine);
        order.verify(jobs).matchingSnapshot(1L);
        order.verify(resumes).matchingSnapshot(2L);
        order.verify(engine).match(job, candidate);

        assertEquals(expected(job, candidate, result), response);
        assertEquals(2L, response.candidateProfileId());
        assertEquals(1L, response.jobId());
        assertEquals(realEngine.version(), response.algorithmVersion());
        assertEquals(MatchingVocabulary.standard().version(), response.vocabularyVersion());
        assertEquals(candidate.parserVersion(), response.profileParserVersion());
        assertEquals(candidate.assessedOn(), response.profileAssessedOn());
        assertEquals(job.updatedAt(), response.jobUpdatedAt());
        assertEquals(result.overallScore(), response.overallScore());
        assertEquals(result.recommendation(), response.recommendation());
        assertEquals(result.matchedRequiredSkills(), response.matchedRequiredSkills());
        assertEquals(result.missingRequiredSkills(), response.missingRequiredSkills());
        assertEquals(result.matchedPreferredSkills(), response.matchedPreferredSkills());
        assertEquals(result.unmatchedPreferredSkills(), response.unmatchedPreferredSkills());
        assertEquals(result.experienceComparison(), response.experienceComparison());
        assertEquals(result.roleRelevance(), response.roleRelevance());
        assertEquals(result.keywordRelevance(), response.keywordRelevance());
        assertEquals(result.breakdown(), response.breakdown());
        assertEquals(result.appliedCaps(), response.appliedCaps());
        assertEquals(result.strengths(), response.strengths());
        assertEquals(result.gaps(), response.gaps());
        assertEquals(result.warnings(), response.warnings());
        assertEquals(result.unassessedFactors(), response.unassessedFactors());
    }

    @Test
    void missingJobStopsBeforeCandidateLookupAndEngine() {
        when(jobs.matchingSnapshot(9L)).thenThrow(new JobNotFoundException(9L));

        assertThrows(JobNotFoundException.class, () -> service.assess(9L, 2L));
        verifyNoInteractions(resumes, engine);
    }

    @Test
    void missingCandidateStopsBeforeEngine() {
        when(resumes.matchingSnapshot(9L)).thenThrow(new ResumeExceptions.CandidateProfileNotFoundException(9L));

        assertThrows(ResumeExceptions.CandidateProfileNotFoundException.class, () -> service.assess(1L, 9L));
        verify(jobs).matchingSnapshot(1L);
        verifyNoInteractions(engine);
    }

    @Test
    void uncomputableMatchingPropagatesUnchanged() {
        MatchCannotBeComputedException failure = new MatchCannotBeComputedException();
        when(engine.match(job, candidate)).thenThrow(failure);

        assertSame(failure, assertThrows(MatchCannotBeComputedException.class, () -> service.assess(1L, 2L)));
    }

    @Test
    void unexpectedEngineFailurePropagatesUnchanged() {
        RuntimeException failure = new IllegalStateException("secret-engine-detail");
        when(engine.match(job, candidate)).thenThrow(failure);

        assertSame(failure, assertThrows(IllegalStateException.class, () -> service.assess(1L, 2L)));
    }

    @Test
    void resultParityMatchesDirectEngineInvocation() {
        var live = new JobAssessmentService(jobs, resumes, realEngine);
        MatchResponse fromService = live.assess(1L, 2L);
        MatchResult fromEngine = realEngine.match(job, candidate);
        assertEquals(expected(job, candidate, fromEngine), fromService);
    }

    @ParameterizedTest
    @CsvSource({
            "3,36,MEETS_REQUIREMENT",
            "10,12,BELOW_REQUIREMENT",
            "3,,UNKNOWN",
            ",36,NOT_APPLICABLE"
    })
    void representativeExperienceStatesRemainEngineOwned(String minimum, Integer months, Status status) {
        JobMatchingSnapshot experienceJob = jobSnapshot(
                "Role",
                null,
                minimum == null ? List.of("java") : List.of(),
                List.of(),
                minimum
        );
        CandidateMatchingSnapshot experienceCandidate = candidateSnapshot("Java", months);
        when(jobs.matchingSnapshot(1L)).thenReturn(experienceJob);
        when(resumes.matchingSnapshot(2L)).thenReturn(experienceCandidate);

        var live = new JobAssessmentService(jobs, resumes, realEngine);
        MatchResponse response = live.assess(1L, 2L);

        assertEquals(status, response.experienceComparison().status());
        assertEquals(realEngine.match(experienceJob, experienceCandidate).experienceComparison(), response.experienceComparison());
    }

    @Test
    void rawCustomEvidenceAndUnknownObservedExperienceSurviveOrchestration() {
        var evidenceJob = jobSnapshot("Backend Engineer", "payments systems", List.of("Java", "custom-tool"), List.of("Redis"), "2");
        var data = new CandidateProfileData(List.of(), null, 120, CandidateProfileData.Assessment.UNKNOWN,
                List.of(), List.of(), List.of(), List.of(), List.of("backend"), List.of(), List.of("fixture-warning"));
        var evidenceCandidate = new CandidateMatchingSnapshot(2L, data,
                "Built Java custom-tool payments systems", "historic-parser", "historic-vocabulary", LocalDate.of(2020, 1, 2));
        when(jobs.matchingSnapshot(1L)).thenReturn(evidenceJob);
        when(resumes.matchingSnapshot(2L)).thenReturn(evidenceCandidate);
        var liveEngine = org.mockito.Mockito.spy(new DeterministicMatchingEngine());
        var actual = new JobAssessmentService(jobs, resumes, liveEngine).assess(1L, 2L);
        var oracle = realEngine.match(evidenceJob, evidenceCandidate);
        assertEquals(expected(evidenceJob, evidenceCandidate, oracle), actual);
        assertEquals(List.of("custom-tool", "java"), actual.matchedRequiredSkills());
        assertEquals(List.of("backend"), actual.roleRelevance().matchedTerms());
        assertEquals(List.of("payments", "systems"), actual.keywordRelevance().matchedTerms());
        assertEquals(Status.UNKNOWN, actual.experienceComparison().status());
        org.junit.jupiter.api.Assertions.assertNull(actual.experienceComparison().candidateYears());
        assertEquals("historic-parser", actual.profileParserVersion());
        assertEquals(LocalDate.of(2020, 1, 2), actual.profileAssessedOn());
        verify(liveEngine).match(org.mockito.ArgumentMatchers.same(evidenceJob), org.mockito.ArgumentMatchers.same(evidenceCandidate));
        verify(liveEngine).version();
        org.mockito.Mockito.verifyNoMoreInteractions(liveEngine);
    }

    @Test
    void roundedDisplayYearsDoNotDetermineExperienceSatisfaction() {
        var preciseJob = jobSnapshot("Role", null, List.of(), List.of(), "0.17");
        var preciseCandidate = candidateSnapshot("", 2);
        when(jobs.matchingSnapshot(1L)).thenReturn(preciseJob);
        when(resumes.matchingSnapshot(2L)).thenReturn(preciseCandidate);
        var actual = new JobAssessmentService(jobs, resumes, realEngine).assess(1L, 2L);
        assertEquals(expected(preciseJob, preciseCandidate, realEngine.match(preciseJob, preciseCandidate)), actual);
        assertEquals(Status.BELOW_REQUIREMENT, actual.experienceComparison().status());
        assertEquals(new BigDecimal("0.17"), actual.experienceComparison().candidateYears());
    }

    private static MatchResponse expected(JobMatchingSnapshot job, CandidateMatchingSnapshot candidate, MatchResult result) {
        return MatchResponse.from(
                candidate.id(),
                job.id(),
                new DeterministicMatchingEngine().version(),
                MatchingVocabulary.standard().version(),
                candidate.parserVersion(),
                candidate.assessedOn(),
                job.updatedAt(),
                result
        );
    }

    private static JobMatchingSnapshot jobSnapshot(
            String title,
            String description,
            List<String> required,
            List<String> preferred,
            String minimum
    ) {
        return new JobMatchingSnapshot(
                1L,
                title,
                description,
                "Remote",
                new JobRequirementsResponse(
                        required,
                        preferred,
                        minimum == null ? null : new BigDecimal(minimum)
                ),
                LocalDateTime.of(2026, 9, 7, 12, 0)
        );
    }

    private static CandidateMatchingSnapshot candidateSnapshot(String text, Integer months) {
        var parsed = new DeterministicProfileParser().parse(text, LocalDate.of(2026, 9, 7));
        var data = new CandidateProfileData(
                parsed.skills(),
                months,
                months,
                months == null ? CandidateProfileData.Assessment.UNKNOWN : CandidateProfileData.Assessment.KNOWN,
                parsed.workExperience(),
                parsed.education(),
                parsed.projects(),
                parsed.keywords(),
                parsed.roleCategories(),
                parsed.evidence(),
                parsed.warnings()
        );
        return new CandidateMatchingSnapshot(2L, data, text, "rules-v1", "v1", LocalDate.of(2026, 9, 7));
    }
}
