package com.jobcopilot.application;

import com.jobcopilot.discovery.LiveOpportunityListingSet;
import com.jobcopilot.discovery.LiveOpportunityListingSnapshot;
import com.jobcopilot.discovery.LiveOpportunityReadService;
import com.jobcopilot.job.JobMatchingSnapshot;
import com.jobcopilot.job.JobRankingSnapshot;
import com.jobcopilot.job.JobService;
import com.jobcopilot.job.JobStatus;
import com.jobcopilot.job.dto.JobRequirementsResponse;
import com.jobcopilot.matching.DeterministicMatchingEngine;
import com.jobcopilot.matching.MatchResult;
import com.jobcopilot.matching.dto.MatchResponse;
import com.jobcopilot.resume.CandidateFactState;
import com.jobcopilot.resume.CandidateMatchingSnapshot;
import com.jobcopilot.resume.CandidateProfileData;
import com.jobcopilot.resume.CandidateProfileFacts;
import com.jobcopilot.resume.JobSearchPreferenceService;
import com.jobcopilot.resume.ResumePersistenceService;
import com.jobcopilot.resume.ResumeRouteService;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LiveOpportunityServiceTest {
    @Test
    void preloadsCandidatePreferenceAndRoutesAndUsesSnapshotPoliciesForTheBatch() {
        ResumePersistenceService profiles = mock(ResumePersistenceService.class);
        JobSearchPreferenceService preferences = mock(JobSearchPreferenceService.class);
        LiveOpportunityReadService listings = mock(LiveOpportunityReadService.class);
        JobService jobs = mock(JobService.class);
        ApplicationDecisionService decisions = mock(ApplicationDecisionService.class);
        ApplicationReadinessService readiness = mock(ApplicationReadinessService.class);
        ResumeRouteService routes = mock(ResumeRouteService.class);
        CandidateProfileData data = new CandidateProfileData(List.of("java"), null, 0,
                CandidateProfileData.Assessment.UNKNOWN, List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of());
        CandidateMatchingSnapshot candidate = new CandidateMatchingSnapshot(7L, data, "rules-v1", "v1",
                LocalDate.of(2026, 9, 15), 4);
        CandidateProfileFacts facts = new CandidateProfileFacts("Candidate", "candidate@example.com", null,
                null, null, null, CandidateFactState.YES, CandidateFactState.NO, null);
        List<JobRankingSnapshot> jobSnapshots = List.of(job(11L), job(12L));
        List<LiveOpportunityListingSnapshot> listingSnapshots = List.of(listing(11L), listing(12L));
        when(profiles.matchingSnapshot(7L)).thenReturn(candidate);
        when(profiles.facts(7L)).thenReturn(facts);
        when(preferences.find(7L)).thenReturn(null);
        when(routes.resolutionCandidates(7L)).thenReturn(List.of());
        when(listings.find(Set.of(JobStatus.DISCOVERED, JobStatus.SHORTLISTED)))
                .thenReturn(new LiveOpportunityListingSet(2, listingSnapshots));
        when(jobs.rankingSnapshotsByIds(Set.of(11L, 12L))).thenReturn(jobSnapshots);
        when(decisions.decide(anyLong(), eq(7L), any(MatchResponse.class), same(facts), isNull(), anyString()))
                .thenAnswer(call -> new ApplicationDecisionResult(call.getArgument(0), 7L,
                        ApplicationDecision.SAVE, List.of("WEAK_MATCH_SAVE_FOR_REVIEW"),
                        call.<MatchResponse>getArgument(2).overallScore(), MatchResult.Recommendation.WEAK_MATCH,
                        true, null, ApplicationDecisionPolicy.VERSION));
        when(readiness.evaluate(any(), eq(7L), any(MatchResponse.class), any(ApplicationDecisionResult.class),
                same(facts), isNull(), any(), isNull())).thenAnswer(call -> {
                    ApplicationDecisionResult decision = call.getArgument(3);
                    return new ApplicationReadinessResult(decision.jobId(), 7L, ApplicationReadiness.NOT_READY,
                            decision, null, List.of());
                });

        var service = new LiveOpportunityService(profiles, preferences, listings, jobs,
                new DeterministicMatchingEngine(), decisions, readiness, routes);
        var response = service.find(7L, OpportunityQuery.from(Map.of()));

        assertEquals(2, response.opportunities().size());
        verify(profiles).matchingSnapshot(7L);
        verify(profiles).facts(7L);
        verify(preferences).find(7L);
        verify(routes).resolutionCandidates(7L);
        verify(jobs).rankingSnapshotsByIds(Set.of(11L, 12L));
        verify(jobs, never()).rankingSnapshots(anySet());
        verify(decisions, times(2)).decide(anyLong(), eq(7L), any(MatchResponse.class),
                same(facts), isNull(), anyString());
        verify(decisions, never()).decide(anyLong(), anyLong(), any(MatchResponse.class));
        verify(readiness, times(2)).evaluate(any(), eq(7L), any(MatchResponse.class),
                any(ApplicationDecisionResult.class), same(facts), isNull(), any(), isNull());
        verify(readiness, never()).evaluate(anyLong(), anyLong(), any(MatchResponse.class),
                any(ApplicationDecisionResult.class));
    }

    private static JobRankingSnapshot job(long id) {
        LocalDateTime now = LocalDateTime.of(2026, 9, 15, 10, 0);
        return new JobRankingSnapshot(new JobMatchingSnapshot(id, "Backend Engineer", "Java", "Remote",
                new JobRequirementsResponse(List.of("java"), List.of(), null), now),
                "Acme", "https://jobs.example.test/" + id, JobStatus.DISCOVERED, now);
    }

    private static LiveOpportunityListingSnapshot listing(long jobId) {
        return new LiveOpportunityListingSnapshot(jobId, 1L, "external-" + jobId, jobId,
                "https://jobs.example.test/" + jobId, "https://apply.example.test/" + jobId,
                LocalDateTime.of(2026, 9, 15, 10, 0));
    }
}
