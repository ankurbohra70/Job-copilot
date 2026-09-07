package com.jobcopilot.matching;

import com.jobcopilot.common.text.MatchingVocabulary;
import com.jobcopilot.job.JobMatchingSnapshot;
import com.jobcopilot.job.JobRankingSnapshot;
import com.jobcopilot.job.JobService;
import com.jobcopilot.job.JobStatus;
import com.jobcopilot.job.dto.JobRequirementsResponse;
import com.jobcopilot.resume.CandidateMatchingSnapshot;
import com.jobcopilot.resume.DeterministicProfileParser;
import com.jobcopilot.resume.ResumeExceptions.CandidateProfileNotFoundException;
import com.jobcopilot.resume.ResumePersistenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static com.jobcopilot.matching.MatchResult.ExperienceComparison;
import static com.jobcopilot.matching.MatchResult.Recommendation;
import static com.jobcopilot.matching.MatchResult.Relevance;
import static com.jobcopilot.matching.MatchResult.Status;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JobRankingServiceTest {
    private ResumePersistenceService resumes;
    private JobService jobs;
    private DeterministicMatchingEngine engine;
    private JobRankingService service;
    private CandidateMatchingSnapshot candidate;

    @BeforeEach
    void setUp() {
        resumes = mock(ResumePersistenceService.class);
        jobs = mock(JobService.class);
        engine = mock(DeterministicMatchingEngine.class);
        service = new JobRankingService(resumes, jobs, engine);
        candidate = candidate();
        when(resumes.matchingSnapshot(2L)).thenReturn(candidate);
        when(engine.version()).thenReturn("from-engine");
    }

    @Test
    void higherNumericScoreRanksBeforeLowerScore() {
        var low = snapshot(1L, "older", LocalDateTime.of(2026, 9, 7, 12, 0));
        var high = snapshot(2L, "newer", LocalDateTime.of(2026, 9, 7, 8, 0));
        when(jobs.rankingSnapshots()).thenReturn(List.of(low, high));
        when(engine.match(low.matching(), candidate)).thenReturn(result("40.00", Recommendation.NOT_RECOMMENDED));
        when(engine.match(high.matching(), candidate)).thenReturn(result("90.00", Recommendation.STRONG_MATCH));

        var response = service.rank(2L);

        assertEquals(List.of(2L, 1L), response.rankedJobs().stream().map(item -> item.job().id()).toList());
        assertEquals(List.of(1, 2), response.rankedJobs().stream().map(item -> item.rank()).toList());
    }

    @Test
    void equalScoreUsesCreatedAtDescendingThenIdDescending() {
        var oldest = snapshot(10L, "a", LocalDateTime.of(2026, 9, 1, 0, 0));
        var newerLowId = snapshot(11L, "b", LocalDateTime.of(2026, 9, 2, 0, 0));
        var newerHighId = snapshot(12L, "c", LocalDateTime.of(2026, 9, 2, 0, 0));
        when(jobs.rankingSnapshots()).thenReturn(List.of(oldest, newerLowId, newerHighId));
        when(engine.match(any(), any())).thenReturn(result("50.00", Recommendation.WEAK_MATCH));

        var response = service.rank(2L);

        assertEquals(List.of(12L, 11L, 10L), response.rankedJobs().stream().map(item -> item.job().id()).toList());
        assertEquals(List.of(1, 2, 3), response.rankedJobs().stream().map(item -> item.rank()).toList());
    }

    @Test
    void numericallyEqualScoresIgnoreScaleAndStatusThenUseCloseTimestampAndIdTies() {
        LocalDateTime olderByOneNanosecond = LocalDateTime.of(2026, 9, 7, 12, 0, 0, 100);
        LocalDateTime newer = olderByOneNanosecond.plusNanos(1);
        var rejected = snapshot(30L, "rejected", olderByOneNanosecond, JobStatus.REJECTED);
        var discovered = snapshot(20L, "discovered", newer, JobStatus.DISCOVERED);
        var withdrawn = snapshot(40L, "withdrawn", newer, JobStatus.WITHDRAWN);
        when(jobs.rankingSnapshots()).thenReturn(List.of(rejected, discovered, withdrawn));
        when(engine.match(rejected.matching(), candidate)).thenReturn(result("80.0", Recommendation.STRONG_MATCH));
        when(engine.match(discovered.matching(), candidate)).thenReturn(result("80.00", Recommendation.STRONG_MATCH));
        when(engine.match(withdrawn.matching(), candidate)).thenReturn(result("80.000", Recommendation.STRONG_MATCH));

        var response = service.rank(2L);

        assertEquals(List.of(40L, 20L, 30L), response.rankedJobs().stream().map(item -> item.job().id()).toList());
        assertEquals(List.of(JobStatus.WITHDRAWN, JobStatus.DISCOVERED, JobStatus.REJECTED),
                response.rankedJobs().stream().map(item -> item.job().status()).toList());
        assertEquals(List.of(1, 2, 3), response.rankedJobs().stream().map(item -> item.rank()).toList());
    }

    @Test
    void recommendationIsNeverUsedAsASortKey() {
        var lowerScoreStrongRecommendation = snapshot(1L, "lower", LocalDateTime.of(2026, 9, 8, 0, 0));
        var higherScoreWeakRecommendation = snapshot(2L, "higher", LocalDateTime.of(2026, 9, 7, 0, 0));
        when(jobs.rankingSnapshots()).thenReturn(List.of(lowerScoreStrongRecommendation, higherScoreWeakRecommendation));
        when(engine.match(lowerScoreStrongRecommendation.matching(), candidate))
                .thenReturn(result("79.99", Recommendation.STRONG_MATCH));
        when(engine.match(higherScoreWeakRecommendation.matching(), candidate))
                .thenReturn(result("80.00", Recommendation.NOT_RECOMMENDED));

        var response = service.rank(2L);

        assertEquals(List.of(2L, 1L), response.rankedJobs().stream().map(item -> item.job().id()).toList());
    }

    @Test
    void experienceOnlyJobWithUnknownCandidateExperienceIsRankedNotUnassessed() {
        var matching = new JobMatchingSnapshot(
                9L,
                "Role",
                null,
                null,
                new JobRequirementsResponse(List.of(), List.of(), new BigDecimal("3")),
                LocalDateTime.of(2026, 9, 7, 0, 0)
        );
        var snapshot = new JobRankingSnapshot(
                matching, "Example", null, JobStatus.DISCOVERED, LocalDateTime.of(2026, 9, 7, 0, 0));
        when(jobs.rankingSnapshots()).thenReturn(List.of(snapshot));
        var realEngine = new DeterministicMatchingEngine();
        var realService = new JobRankingService(resumes, jobs, realEngine);

        var response = realService.rank(2L);

        assertEquals(1, response.rankedJobCount());
        assertEquals(0, response.unassessedJobCount());
        assertEquals(Status.UNKNOWN, response.rankedJobs().getFirst().match().experienceComparison().status());
        assertEquals(null, response.rankedJobs().getFirst().match().experienceComparison().candidateYears());
    }

    @Test
    void singleJobReceivesRankOne() {
        var only = snapshot(5L, "only", LocalDateTime.of(2026, 9, 7, 0, 0));
        when(jobs.rankingSnapshots()).thenReturn(List.of(only));
        when(engine.match(only.matching(), candidate)).thenReturn(result("12.34", Recommendation.NOT_RECOMMENDED));

        var response = service.rank(2L);

        assertEquals(1, response.rankedJobs().getFirst().rank());
        assertEquals(1, response.rankedJobCount());
        assertEquals(0, response.unassessedJobCount());
        assertEquals(1, response.evaluatedJobCount());
    }

    @Test
    void noJobsReturnsEmptyCountsAndDoesNotCallTheEngine() {
        when(jobs.rankingSnapshots()).thenReturn(List.of());

        var response = service.rank(2L);

        assertEquals(0, response.evaluatedJobCount());
        assertEquals(0, response.rankedJobCount());
        assertEquals(0, response.unassessedJobCount());
        assertTrue(response.rankedJobs().isEmpty());
        assertTrue(response.unassessedJobs().isEmpty());
        verify(engine, never()).match(any(), any());
        verify(engine).version();
    }

    @Test
    void unassessedJobsRemainSeparateAndAllUnassessedIsStillACompleteResponse() {
        var computable = snapshot(1L, "scored", LocalDateTime.of(2026, 9, 7, 1, 0));
        var olderUnassessed = snapshot(2L, "old-gap", LocalDateTime.of(2026, 9, 6, 0, 0));
        var newerUnassessed = snapshot(3L, "new-gap", LocalDateTime.of(2026, 9, 8, 0, 0));
        when(jobs.rankingSnapshots()).thenReturn(List.of(computable, olderUnassessed, newerUnassessed));
        MatchResult computed = result("70.00", Recommendation.GOOD_MATCH);
        when(engine.match(computable.matching(), candidate)).thenReturn(computed);
        when(engine.match(olderUnassessed.matching(), candidate)).thenThrow(new MatchCannotBeComputedException());
        when(engine.match(newerUnassessed.matching(), candidate)).thenThrow(new MatchCannotBeComputedException());

        var mixed = service.rank(2L);

        assertEquals(3, mixed.evaluatedJobCount());
        assertEquals(1, mixed.rankedJobCount());
        assertEquals(2, mixed.unassessedJobCount());
        assertEquals(1, mixed.rankedJobs().getFirst().rank());
        assertEquals(1L, mixed.rankedJobs().getFirst().job().id());
        assertEquals(List.of(3L, 2L), mixed.unassessedJobs().stream().map(item -> item.job().id()).toList());
        mixed.unassessedJobs().forEach(item -> {
            assertEquals(com.jobcopilot.matching.dto.UnassessedJobResponse.Reason.INSUFFICIENT_JOB_REQUIREMENTS, item.reason());
            assertEquals(new MatchCannotBeComputedException().getMessage(), item.message());
        });

        when(jobs.rankingSnapshots()).thenReturn(List.of(olderUnassessed, newerUnassessed));
        var allUnassessed = service.rank(2L);
        assertTrue(allUnassessed.rankedJobs().isEmpty());
        assertEquals(2, allUnassessed.unassessedJobCount());
        assertEquals(2, allUnassessed.evaluatedJobCount());
    }

    @Test
    void missingCandidatePropagatesAndPreventsJobLoading() {
        when(resumes.matchingSnapshot(9L)).thenThrow(new CandidateProfileNotFoundException(9L));

        assertThrows(CandidateProfileNotFoundException.class, () -> service.rank(9L));

        verify(jobs, never()).rankingSnapshots();
        verify(engine, never()).match(any(), any());
    }

    @Test
    void onlyMatchCannotBeComputedBecomesUnassessedWhileUnexpectedFailuresAbort() {
        var first = snapshot(1L, "ok", LocalDateTime.of(2026, 9, 7, 0, 0));
        var second = snapshot(2L, "boom", LocalDateTime.of(2026, 9, 7, 1, 0));
        when(jobs.rankingSnapshots()).thenReturn(List.of(first, second));
        when(engine.match(first.matching(), candidate)).thenReturn(result("80.00", Recommendation.STRONG_MATCH));
        when(engine.match(second.matching(), candidate)).thenThrow(new IllegalStateException("corrupt snapshot"));

        JobRankingComputationException exception = assertThrows(JobRankingComputationException.class, () -> service.rank(2L));
        assertEquals("Unexpected ranking failure for job 2", exception.getMessage());
        assertEquals("corrupt snapshot", exception.getCause().getMessage());
    }

    @Test
    void scoreRecommendationAndExplanationsComeFromTheEngineWithoutExtraCalculation() {
        var job = snapshot(8L, "explained", LocalDateTime.of(2026, 9, 7, 0, 0));
        MatchResult computed = new MatchResult(
                new BigDecimal("66.67"),
                Recommendation.GOOD_MATCH,
                List.of("java"),
                List.of("redis"),
                List.of("docker"),
                List.of("aws"),
                new ExperienceComparison(new BigDecimal("3"), new BigDecimal("2.00"), Status.BELOW_REQUIREMENT, new BigDecimal("66.67")),
                new Relevance(Status.ASSESSED, new BigDecimal("100.00"), List.of("backend")),
                new Relevance(Status.ASSESSED, new BigDecimal("50.00"), List.of("payments")),
                List.of(),
                List.of(new MatchResult.Cap("MISSING_REQUIRED_SKILL", new BigDecimal("79"))),
                List.of(new MatchResult.Explanation("REQUIRED_SKILL_MATCH", "Recognized evidence for java", List.of("Java"))),
                List.of(new MatchResult.Explanation("MISSING_REQUIRED_SKILL", "No evidence recognized for required skill: redis", List.of())),
                List.of("parser warning"),
                List.of("LOCATION_WORK_MODE")
        );
        when(jobs.rankingSnapshots()).thenReturn(List.of(job));
        when(engine.match(job.matching(), candidate)).thenReturn(computed);

        var response = service.rank(2L);
        var match = response.rankedJobs().getFirst().match();

        assertSame(computed.overallScore(), match.overallScore());
        assertEquals(computed.recommendation(), match.recommendation());
        assertEquals(computed.matchedRequiredSkills(), match.matchedRequiredSkills());
        assertEquals(computed.missingRequiredSkills(), match.missingRequiredSkills());
        assertEquals(computed.matchedPreferredSkills(), match.matchedPreferredSkills());
        assertEquals(computed.unmatchedPreferredSkills(), match.unmatchedPreferredSkills());
        assertEquals(computed.experienceComparison(), match.experienceComparison());
        assertEquals(computed.appliedCaps(), match.appliedCaps());
        assertEquals(computed.strengths(), match.strengths());
        assertEquals(computed.gaps(), match.gaps());
        assertEquals(computed.warnings(), match.warnings());
        assertEquals(computed.unassessedFactors(), match.unassessedFactors());
        verify(engine).match(job.matching(), candidate);
    }

    @Test
    void rankingDoesNotMutateInputSnapshots() {
        var job = snapshot(8L, "stable", LocalDateTime.of(2026, 9, 7, 0, 0));
        var originalMatching = job.matching();
        var originalRequirements = originalMatching.requirements();
        when(jobs.rankingSnapshots()).thenReturn(List.of(job));
        when(engine.match(job.matching(), candidate)).thenReturn(result("10.00", Recommendation.NOT_RECOMMENDED));

        service.rank(2L);

        assertSame(originalMatching, job.matching());
        assertEquals(originalRequirements, job.matching().requirements());
        assertThrows(UnsupportedOperationException.class, () -> job.matching().requirements().requiredSkills().add("invented"));
        assertEquals(JobStatus.REJECTED, job.status());
    }

    @Test
    void versionMetadataIsSourcedFromExistingAuthorities() {
        when(jobs.rankingSnapshots()).thenReturn(List.of());
        when(engine.version()).thenReturn("from-engine");

        var response = service.rank(2L);

        assertEquals(2L, response.candidateProfileId());
        assertEquals("from-engine", response.algorithmVersion());
        assertEquals(MatchingVocabulary.standard().version(), response.vocabularyVersion());
        assertEquals(candidate.parserVersion(), response.profileParserVersion());
        assertEquals(candidate.assessedOn(), response.profileAssessedOn());
        verify(engine).version();
    }

    @Test
    void candidateIsLoadedBeforeJobs() {
        when(jobs.rankingSnapshots()).thenReturn(List.of());

        service.rank(2L);

        InOrder order = inOrder(resumes, jobs);
        order.verify(resumes).matchingSnapshot(2L);
        order.verify(jobs).rankingSnapshots();
    }

    private static JobRankingSnapshot snapshot(Long id, String title, LocalDateTime createdAt) {
        return snapshot(id, title, createdAt, JobStatus.REJECTED);
    }

    private static JobRankingSnapshot snapshot(Long id, String title, LocalDateTime createdAt, JobStatus status) {
        var matching = new JobMatchingSnapshot(
                id,
                title,
                "Java backend",
                "Remote",
                new JobRequirementsResponse(List.of("java"), List.of(), null),
                createdAt.plusHours(1)
        );
        return new JobRankingSnapshot(matching, "Example", "https://example.com/jobs/" + id, status, createdAt);
    }

    private static CandidateMatchingSnapshot candidate() {
        String text = "Java";
        var parsed = new DeterministicProfileParser().parse(text, LocalDate.of(2026, 9, 7));
        return new CandidateMatchingSnapshot(2L, parsed, text, "persisted-parser", "stored-vocab", LocalDate.of(2026, 9, 6));
    }

    private static MatchResult result(String score, Recommendation recommendation) {
        return new MatchResult(
                new BigDecimal(score),
                recommendation,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                new ExperienceComparison(null, null, Status.NOT_APPLICABLE, null),
                new Relevance(Status.NOT_APPLICABLE, null, List.of()),
                new Relevance(Status.NOT_APPLICABLE, null, List.of()),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
    }
}
