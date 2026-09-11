package com.jobcopilot.matching;

import com.jobcopilot.common.text.MatchingVocabulary;
import com.jobcopilot.job.JobMatchingSnapshot;
import com.jobcopilot.job.JobRankingSnapshot;
import com.jobcopilot.job.JobService;
import com.jobcopilot.job.JobStatus;
import com.jobcopilot.job.dto.JobRequirementsResponse;
import com.jobcopilot.matching.dto.JobRankingResponse;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static com.jobcopilot.matching.MatchResult.ExperienceComparison;
import static com.jobcopilot.matching.MatchResult.Recommendation;
import static com.jobcopilot.matching.MatchResult.Relevance;
import static com.jobcopilot.matching.MatchResult.Status;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
    void defaultQueryAsksJobServiceForDefaultEligibleStatusesOnly() {
        when(jobs.rankingSnapshots(any())).thenReturn(List.of());

        rank();

        verify(jobs).rankingSnapshots(JobRankingQuery.DEFAULT_STATUSES);
        verify(jobs, never()).rankingSnapshots(eq(Set.of(JobStatus.OFFER)));
    }

    @Test
    void explicitStatusesArePassedThroughWithoutIntersectingDefaults() {
        when(jobs.rankingSnapshots(any())).thenReturn(List.of());

        rank(query(List.of("OFFER"), null, null, null, null));
        verify(jobs).rankingSnapshots(Set.of(JobStatus.OFFER));

        rank(query(List.of("REJECTED"), null, null, null, null));
        verify(jobs).rankingSnapshots(Set.of(JobStatus.REJECTED));

        rank(query(List.of("WITHDRAWN"), null, null, null, null));
        verify(jobs).rankingSnapshots(Set.of(JobStatus.WITHDRAWN));

        rank(query(List.of("DISCOVERED", "OFFER"), null, null, null, null));
        verify(jobs).rankingSnapshots(Set.of(JobStatus.DISCOVERED, JobStatus.OFFER));
    }

    @Test
    void duplicateStatusesDoNotCauseDuplicateEvaluation() {
        var job = snapshot(8L, "once", LocalDateTime.of(2026, 9, 7, 0, 0), JobStatus.DISCOVERED);
        when(jobs.rankingSnapshots(Set.of(JobStatus.DISCOVERED))).thenReturn(List.of(job));
        when(engine.match(job.matching(), candidate)).thenReturn(result("90.00", Recommendation.STRONG_MATCH));

        var response = rank(query(List.of("DISCOVERED", "DISCOVERED"), null, null, null, null));

        assertEquals(1, response.evaluatedJobCount());
        assertEquals(List.of(8L), response.rankedJobs().stream().map(item -> item.job().id()).toList());
        verify(engine, times(1)).match(job.matching(), candidate);
        verify(jobs).rankingSnapshots(Set.of(JobStatus.DISCOVERED));
    }

    @Test
    void higherNumericScoreRanksBeforeLowerScore() {
        var low = snapshot(1L, "older", LocalDateTime.of(2026, 9, 7, 12, 0));
        var high = snapshot(2L, "newer", LocalDateTime.of(2026, 9, 7, 8, 0));
        when(jobs.rankingSnapshots(any())).thenReturn(List.of(low, high));
        when(engine.match(low.matching(), candidate)).thenReturn(result("40.00", Recommendation.NOT_RECOMMENDED));
        when(engine.match(high.matching(), candidate)).thenReturn(result("90.00", Recommendation.STRONG_MATCH));

        var response = rank();

        assertEquals(List.of(2L, 1L), response.rankedJobs().stream().map(item -> item.job().id()).toList());
        assertEquals(List.of(1, 2), response.rankedJobs().stream().map(item -> item.rank()).toList());
    }

    @Test
    void repositoryOrderDoesNotControlGlobalPageZero() {
        List<JobRankingSnapshot> snapshots = new ArrayList<>();
        for (int index = 1; index <= 5; index++) {
            snapshots.add(snapshot((long) index, "job-" + index, LocalDateTime.of(2026, 9, index, 0, 0)));
            when(engine.match(snapshots.getLast().matching(), candidate))
                    .thenReturn(result(String.valueOf(10 * index), Recommendation.WEAK_MATCH));
        }
        when(jobs.rankingSnapshots(any())).thenReturn(snapshots);

        var pageZero = rank(query(null, null, null, List.of("0"), List.of("2")));

        assertEquals(List.of(5L, 4L), pageZero.rankedJobs().stream().map(item -> item.job().id()).toList());
        assertEquals(List.of(1, 2), pageZero.rankedJobs().stream().map(item -> item.rank()).toList());
        verify(engine, times(5)).match(any(), any());
    }

    @Test
    void paginationSlicesGloballyRankedResultsWithoutResettingRanks() {
        List<JobRankingSnapshot> snapshots = new ArrayList<>();
        for (int index = 1; index <= 5; index++) {
            snapshots.add(snapshot((long) index, "job-" + index, LocalDateTime.of(2026, 9, 7, index, 0)));
            when(engine.match(snapshots.getLast().matching(), candidate))
                    .thenReturn(result(new BigDecimal(index * 10).toPlainString(), Recommendation.WEAK_MATCH));
        }
        when(jobs.rankingSnapshots(any())).thenReturn(snapshots);

        var page0 = rank(query(null, null, null, List.of("0"), List.of("2")));
        var page1 = rank(query(null, null, null, List.of("1"), List.of("2")));
        var last = rank(query(null, null, null, List.of("2"), List.of("2")));
        var beyond = rank(query(null, null, null, List.of("3"), List.of("2")));

        assertEquals(List.of(5L, 4L), page0.rankedJobs().stream().map(item -> item.job().id()).toList());
        assertEquals(List.of(1, 2), page0.rankedJobs().stream().map(item -> item.rank()).toList());
        assertEquals(List.of(3L, 2L), page1.rankedJobs().stream().map(item -> item.job().id()).toList());
        assertEquals(List.of(3, 4), page1.rankedJobs().stream().map(item -> item.rank()).toList());
        assertEquals(List.of(1L), last.rankedJobs().stream().map(item -> item.job().id()).toList());
        assertEquals(List.of(5), last.rankedJobs().stream().map(item -> item.rank()).toList());
        assertEquals(1, last.pageResultCount());
        assertTrue(beyond.rankedJobs().isEmpty());
        assertEquals(0, beyond.pageResultCount());
        assertEquals(5, beyond.filteredJobCount());
        assertEquals(3, beyond.totalPages());
        assertFalse(beyond.first());
        assertTrue(beyond.last());
        assertCountInvariants(page0);
        assertCountInvariants(beyond);
    }

    @Test
    void equalScoreTiesContinueAcrossPageBoundaries() {
        var oldest = snapshot(10L, "a", LocalDateTime.of(2026, 9, 1, 0, 0));
        var newerLowId = snapshot(11L, "b", LocalDateTime.of(2026, 9, 2, 0, 0));
        var newerHighId = snapshot(12L, "c", LocalDateTime.of(2026, 9, 2, 0, 0));
        when(jobs.rankingSnapshots(any())).thenReturn(List.of(oldest, newerLowId, newerHighId));
        when(engine.match(any(), any())).thenReturn(result("50.00", Recommendation.WEAK_MATCH));

        var page0 = rank(query(null, null, null, List.of("0"), List.of("2")));
        var page1 = rank(query(null, null, null, List.of("1"), List.of("2")));

        assertEquals(List.of(12L, 11L), page0.rankedJobs().stream().map(item -> item.job().id()).toList());
        assertEquals(List.of(1, 2), page0.rankedJobs().stream().map(item -> item.rank()).toList());
        assertEquals(List.of(10L), page1.rankedJobs().stream().map(item -> item.job().id()).toList());
        assertEquals(List.of(3), page1.rankedJobs().stream().map(item -> item.rank()).toList());
    }

    @Test
    void numericallyEqualScoresIgnoreScaleAndStatusThenUseCloseTimestampAndIdTies() {
        LocalDateTime olderByOneNanosecond = LocalDateTime.of(2026, 9, 7, 12, 0, 0, 100);
        LocalDateTime newer = olderByOneNanosecond.plusNanos(1);
        var rejected = snapshot(30L, "rejected", olderByOneNanosecond, JobStatus.REJECTED);
        var discovered = snapshot(20L, "discovered", newer, JobStatus.DISCOVERED);
        var withdrawn = snapshot(40L, "withdrawn", newer, JobStatus.WITHDRAWN);
        when(jobs.rankingSnapshots(any())).thenReturn(List.of(rejected, discovered, withdrawn));
        when(engine.match(rejected.matching(), candidate)).thenReturn(result("80.0", Recommendation.STRONG_MATCH));
        when(engine.match(discovered.matching(), candidate)).thenReturn(result("80.00", Recommendation.STRONG_MATCH));
        when(engine.match(withdrawn.matching(), candidate)).thenReturn(result("80.000", Recommendation.STRONG_MATCH));

        var response = rank();

        assertEquals(List.of(40L, 20L, 30L), response.rankedJobs().stream().map(item -> item.job().id()).toList());
        assertEquals(List.of(JobStatus.WITHDRAWN, JobStatus.DISCOVERED, JobStatus.REJECTED),
                response.rankedJobs().stream().map(item -> item.job().status()).toList());
        assertEquals(List.of(1, 2, 3), response.rankedJobs().stream().map(item -> item.rank()).toList());
    }

    @Test
    void recommendationIsNeverUsedAsASortKey() {
        var lowerScoreStrongRecommendation = snapshot(1L, "lower", LocalDateTime.of(2026, 9, 8, 0, 0));
        var higherScoreWeakRecommendation = snapshot(2L, "higher", LocalDateTime.of(2026, 9, 7, 0, 0));
        when(jobs.rankingSnapshots(any())).thenReturn(List.of(lowerScoreStrongRecommendation, higherScoreWeakRecommendation));
        when(engine.match(lowerScoreStrongRecommendation.matching(), candidate))
                .thenReturn(result("79.99", Recommendation.STRONG_MATCH));
        when(engine.match(higherScoreWeakRecommendation.matching(), candidate))
                .thenReturn(result("80.00", Recommendation.NOT_RECOMMENDED));

        var response = rank();

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
        when(jobs.rankingSnapshots(any())).thenReturn(List.of(snapshot));
        var realEngine = new DeterministicMatchingEngine();
        var realService = new JobRankingService(resumes, jobs, realEngine);

        var response = realService.rank(2L, defaults());

        assertEquals(1, response.computableJobCount());
        assertEquals(1, response.filteredJobCount());
        assertEquals(0, response.unassessedJobCount());
        assertEquals(Status.UNKNOWN, response.rankedJobs().getFirst().match().experienceComparison().status());
        assertEquals(null, response.rankedJobs().getFirst().match().experienceComparison().candidateYears());
    }

    @Test
    void singleJobReceivesRankOne() {
        var only = snapshot(5L, "only", LocalDateTime.of(2026, 9, 7, 0, 0));
        when(jobs.rankingSnapshots(any())).thenReturn(List.of(only));
        when(engine.match(only.matching(), candidate)).thenReturn(result("12.34", Recommendation.NOT_RECOMMENDED));

        var response = rank();

        assertEquals(1, response.rankedJobs().getFirst().rank());
        assertEquals(1, response.computableJobCount());
        assertEquals(1, response.filteredJobCount());
        assertEquals(1, response.pageResultCount());
        assertEquals(0, response.unassessedJobCount());
        assertEquals(1, response.evaluatedJobCount());
        assertCountInvariants(response);
    }

    @Test
    void noJobsReturnsEmptyCountsAndDoesNotCallTheEngine() {
        when(jobs.rankingSnapshots(any())).thenReturn(List.of());

        var response = rank();

        assertEquals(0, response.evaluatedJobCount());
        assertEquals(0, response.computableJobCount());
        assertEquals(0, response.filteredJobCount());
        assertEquals(0, response.pageResultCount());
        assertEquals(0, response.unassessedJobCount());
        assertEquals(0, response.totalPages());
        assertTrue(response.first());
        assertTrue(response.last());
        assertTrue(response.rankedJobs().isEmpty());
        assertTrue(response.unassessedJobs().isEmpty());
        verify(engine, never()).match(any(), any());
        verify(engine).version();
        assertCountInvariants(response);
    }

    @Test
    void defaultPageSizeIsTwentyAndSizeOneHundredIsAccepted() {
        List<JobRankingSnapshot> snapshots = IntStream.rangeClosed(1, 25)
                .mapToObj(index -> snapshot((long) index, "job-" + index, LocalDateTime.of(2026, 9, 7, 0, index)))
                .toList();
        when(jobs.rankingSnapshots(any())).thenReturn(snapshots);
        snapshots.forEach(snapshot -> when(engine.match(snapshot.matching(), candidate))
                .thenReturn(result(BigDecimal.valueOf(snapshot.matching().id()).toPlainString(), Recommendation.WEAK_MATCH)));

        var defaultPage = rank();
        var large = rank(query(null, null, null, List.of("0"), List.of("100")));

        assertEquals(20, defaultPage.size());
        assertEquals(20, defaultPage.pageResultCount());
        assertEquals(25, defaultPage.filteredJobCount());
        assertEquals(2, defaultPage.totalPages());
        assertEquals(IntStream.rangeClosed(1, 20).boxed().toList(),
                defaultPage.rankedJobs().stream().map(item -> item.rank()).toList());
        assertEquals(100, large.size());
        assertEquals(25, large.pageResultCount());
        assertEquals(1, large.totalPages());
        verify(engine, times(50)).match(any(), any());
    }

    @Test
    void veryLargePageDoesNotOverflowAndReturnsEmptyRankedPage() {
        var only = snapshot(5L, "only", LocalDateTime.of(2026, 9, 7, 0, 0));
        when(jobs.rankingSnapshots(any())).thenReturn(List.of(only));
        when(engine.match(only.matching(), candidate)).thenReturn(result("12.34", Recommendation.NOT_RECOMMENDED));

        var response = rank(query(null, null, null, List.of(String.valueOf(Integer.MAX_VALUE)), List.of("100")));

        assertTrue(response.rankedJobs().isEmpty());
        assertEquals(0, response.pageResultCount());
        assertEquals(1, response.filteredJobCount());
        assertEquals(Integer.MAX_VALUE, response.page());
        assertFalse(response.first());
        assertTrue(response.last());
        assertCountInvariants(response);
    }

    @Test
    void minScoreFiltersComputableResultsInclusivelyWithoutMutatingEngineOutput() {
        var below = snapshot(1L, "below", LocalDateTime.of(2026, 9, 7, 0, 0));
        var exact = snapshot(2L, "exact", LocalDateTime.of(2026, 9, 7, 1, 0));
        var above = snapshot(3L, "above", LocalDateTime.of(2026, 9, 7, 2, 0));
        when(jobs.rankingSnapshots(any())).thenReturn(List.of(below, exact, above));
        MatchResult exactResult = result("65.00", Recommendation.GOOD_MATCH);
        when(engine.match(below.matching(), candidate)).thenReturn(result("64.99", Recommendation.WEAK_MATCH));
        when(engine.match(exact.matching(), candidate)).thenReturn(exactResult);
        when(engine.match(above.matching(), candidate)).thenReturn(result("65.01", Recommendation.GOOD_MATCH));

        var response = rank(query(null, null, List.of("65.00"), null, null));

        assertEquals(3, response.computableJobCount());
        assertEquals(2, response.filteredJobCount());
        assertEquals(List.of(3L, 2L), response.rankedJobs().stream().map(item -> item.job().id()).toList());
        assertSame(exactResult.overallScore(), response.rankedJobs().get(1).match().overallScore());
        assertEquals(0, rank(query(null, null, List.of("0"), null, null)).filteredJobCount()
                - rank(query(null, null, List.of("0"), null, null)).computableJobCount());
        assertEquals(3, rank(query(null, null, List.of("0"), null, null)).filteredJobCount());
        assertEquals(0, rank(query(null, null, List.of("100"), null, null)).filteredJobCount());
        assertCountInvariants(response);
    }

    @Test
    void recommendationFilterUsesOrWithinTierAndAndWithMinScore() {
        var strong = snapshot(1L, "strong", LocalDateTime.of(2026, 9, 7, 4, 0));
        var good = snapshot(2L, "good", LocalDateTime.of(2026, 9, 7, 3, 0));
        var weak = snapshot(3L, "weak", LocalDateTime.of(2026, 9, 7, 2, 0));
        var none = snapshot(4L, "none", LocalDateTime.of(2026, 9, 7, 1, 0));
        when(jobs.rankingSnapshots(any())).thenReturn(List.of(strong, good, weak, none));
        MatchResult strongResult = result("90.00", Recommendation.STRONG_MATCH);
        when(engine.match(strong.matching(), candidate)).thenReturn(strongResult);
        when(engine.match(good.matching(), candidate)).thenReturn(result("70.00", Recommendation.GOOD_MATCH));
        when(engine.match(weak.matching(), candidate)).thenReturn(result("50.00", Recommendation.WEAK_MATCH));
        when(engine.match(none.matching(), candidate)).thenReturn(result("10.00", Recommendation.NOT_RECOMMENDED));

        assertEquals(List.of(1L), rank(query(null, List.of("STRONG_MATCH"), null, null, null))
                .rankedJobs().stream().map(item -> item.job().id()).toList());
        assertEquals(List.of(2L), rank(query(null, List.of("GOOD_MATCH"), null, null, null))
                .rankedJobs().stream().map(item -> item.job().id()).toList());
        assertEquals(List.of(3L), rank(query(null, List.of("WEAK_MATCH"), null, null, null))
                .rankedJobs().stream().map(item -> item.job().id()).toList());
        assertEquals(List.of(4L), rank(query(null, List.of("NOT_RECOMMENDED"), null, null, null))
                .rankedJobs().stream().map(item -> item.job().id()).toList());
        assertEquals(List.of(1L, 2L), rank(query(null, List.of("STRONG_MATCH", "GOOD_MATCH", "STRONG_MATCH"), null, null, null))
                .rankedJobs().stream().map(item -> item.job().id()).toList());
        var andFilter = rank(query(null, List.of("GOOD_MATCH"), List.of("80"), null, null));
        assertTrue(andFilter.rankedJobs().isEmpty());
        assertEquals(4, andFilter.computableJobCount());
        assertEquals(0, andFilter.filteredJobCount());
        assertSame(strongResult.recommendation(),
                rank(query(null, List.of("STRONG_MATCH"), null, null, null)).rankedJobs().getFirst().match().recommendation());
    }

    @Test
    void unassessedJobsRemainSeparateAndIgnorePostScoreFilters() {
        var computable = snapshot(1L, "scored", LocalDateTime.of(2026, 9, 7, 1, 0), JobStatus.DISCOVERED);
        var olderUnassessed = snapshot(2L, "old-gap", LocalDateTime.of(2026, 9, 6, 0, 0), JobStatus.DISCOVERED);
        var newerUnassessed = snapshot(3L, "new-gap", LocalDateTime.of(2026, 9, 8, 0, 0), JobStatus.DISCOVERED);
        when(jobs.rankingSnapshots(any())).thenReturn(List.of(computable, olderUnassessed, newerUnassessed));
        MatchResult computed = result("70.00", Recommendation.GOOD_MATCH);
        when(engine.match(computable.matching(), candidate)).thenReturn(computed);
        when(engine.match(olderUnassessed.matching(), candidate)).thenThrow(new MatchCannotBeComputedException());
        when(engine.match(newerUnassessed.matching(), candidate)).thenThrow(new MatchCannotBeComputedException());

        var mixed = rank();
        assertEquals(3, mixed.evaluatedJobCount());
        assertEquals(1, mixed.computableJobCount());
        assertEquals(1, mixed.filteredJobCount());
        assertEquals(2, mixed.unassessedJobCount());
        assertEquals(1, mixed.rankedJobs().getFirst().rank());
        assertEquals(1L, mixed.rankedJobs().getFirst().job().id());
        assertEquals(List.of(3L, 2L), mixed.unassessedJobs().stream().map(item -> item.job().id()).toList());
        mixed.unassessedJobs().forEach(item -> {
            assertEquals(com.jobcopilot.matching.dto.UnassessedJobResponse.Reason.INSUFFICIENT_JOB_REQUIREMENTS, item.reason());
            assertEquals(new MatchCannotBeComputedException().getMessage(), item.message());
        });
        assertCountInvariants(mixed);

        var filteredAway = rank(query(null, List.of("STRONG_MATCH"), List.of("90"), List.of("0"), List.of("1")));
        assertEquals(0, filteredAway.filteredJobCount());
        assertEquals(0, filteredAway.pageResultCount());
        assertEquals(2, filteredAway.unassessedJobCount());
        assertEquals(List.of(3L, 2L), filteredAway.unassessedJobs().stream().map(item -> item.job().id()).toList());
        assertEquals(0, filteredAway.totalPages());
        assertTrue(filteredAway.first());
        assertTrue(filteredAway.last());
        assertCountInvariants(filteredAway);

        var differentPage = rank(query(null, null, null, List.of("1"), List.of("1")));
        assertEquals(List.of(3L, 2L), differentPage.unassessedJobs().stream().map(item -> item.job().id()).toList());

        when(jobs.rankingSnapshots(any())).thenReturn(List.of(olderUnassessed, newerUnassessed));
        var allUnassessed = rank();
        assertTrue(allUnassessed.rankedJobs().isEmpty());
        assertEquals(0, allUnassessed.computableJobCount());
        assertEquals(2, allUnassessed.unassessedJobCount());
        assertEquals(2, allUnassessed.evaluatedJobCount());
        assertCountInvariants(allUnassessed);
    }

    @Test
    void missingCandidatePropagatesAndPreventsJobLoading() {
        when(resumes.matchingSnapshot(9L)).thenThrow(new CandidateProfileNotFoundException(9L));

        assertThrows(CandidateProfileNotFoundException.class, () -> service.rank(9L, defaults()));

        verify(jobs, never()).rankingSnapshots(any());
        verify(engine, never()).match(any(), any());
    }

    @Test
    void onlyMatchCannotBeComputedBecomesUnassessedWhileUnexpectedFailuresAbort() {
        var first = snapshot(1L, "ok", LocalDateTime.of(2026, 9, 7, 0, 0));
        var second = snapshot(2L, "boom", LocalDateTime.of(2026, 9, 7, 1, 0));
        var third = snapshot(3L, "must-not-run", LocalDateTime.of(2026, 9, 7, 2, 0));
        when(jobs.rankingSnapshots(any())).thenReturn(List.of(first, second, third));
        when(engine.match(first.matching(), candidate)).thenReturn(result("80.00", Recommendation.STRONG_MATCH));
        when(engine.match(second.matching(), candidate)).thenThrow(new IllegalStateException("corrupt snapshot"));

        JobRankingComputationException exception = assertThrows(JobRankingComputationException.class, () -> rank());
        assertEquals("Unexpected ranking failure for job 2", exception.getMessage());
        assertEquals("corrupt snapshot", exception.getCause().getMessage());
        verify(engine, never()).match(third.matching(), candidate);
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
        when(jobs.rankingSnapshots(any())).thenReturn(List.of(job));
        when(engine.match(job.matching(), candidate)).thenReturn(computed);

        var response = rank();
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
        when(jobs.rankingSnapshots(any())).thenReturn(List.of(job));
        when(engine.match(job.matching(), candidate)).thenReturn(result("10.00", Recommendation.NOT_RECOMMENDED));

        rank();

        assertSame(originalMatching, job.matching());
        assertEquals(originalRequirements, job.matching().requirements());
        assertThrows(UnsupportedOperationException.class, () -> job.matching().requirements().requiredSkills().add("invented"));
        assertEquals(JobStatus.REJECTED, job.status());
    }

    @Test
    void versionMetadataIsSourcedFromExistingAuthorities() {
        when(jobs.rankingSnapshots(any())).thenReturn(List.of());
        when(engine.version()).thenReturn("from-engine");

        var response = rank();

        assertEquals(2L, response.candidateProfileId());
        assertEquals("from-engine", response.algorithmVersion());
        assertEquals(MatchingVocabulary.standard().version(), response.vocabularyVersion());
        assertEquals(candidate.parserVersion(), response.profileParserVersion());
        assertEquals(candidate.assessedOn(), response.profileAssessedOn());
        verify(engine).version();
    }

    @Test
    void candidateIsLoadedBeforeJobs() {
        when(jobs.rankingSnapshots(any())).thenReturn(List.of());

        rank();

        InOrder order = inOrder(resumes, jobs);
        order.verify(resumes).matchingSnapshot(2L);
        order.verify(jobs).rankingSnapshots(JobRankingQuery.DEFAULT_STATUSES);
    }

    private JobRankingResponse rank() {
        return rank(defaults());
    }

    private JobRankingResponse rank(JobRankingQuery query) {
        return service.rank(2L, query);
    }

    private static JobRankingQuery defaults() {
        return JobRankingQuery.parse(null, null, null, null, null);
    }

    private static JobRankingQuery query(
            List<String> status,
            List<String> recommendation,
            List<String> minScore,
            List<String> page,
            List<String> size
    ) {
        return JobRankingQuery.parse(status, recommendation, minScore, page, size);
    }

    private static void assertCountInvariants(JobRankingResponse response) {
        assertEquals(response.computableJobCount() + response.unassessedJobCount(), response.evaluatedJobCount());
        assertTrue(response.filteredJobCount() <= response.computableJobCount());
        assertEquals(response.rankedJobs().size(), response.pageResultCount());
        assertEquals(response.unassessedJobs().size(), response.unassessedJobCount());
        assertTrue(response.pageResultCount() <= response.size());
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
