package com.jobcopilot.application;

import com.jobcopilot.job.*;
import com.jobcopilot.matching.JobAssessmentService;
import com.jobcopilot.matching.MatchResult;
import com.jobcopilot.matching.dto.MatchResponse;
import com.jobcopilot.resume.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ApplicationDecisionServiceTest {
    private JobAssessmentService assessments;
    private ResumePersistenceService profiles;
    private JobSearchPreferenceService preferences;
    private JobService jobs;
    private ApplicationDecisionService service;

    @BeforeEach void setup() {
        assessments = mock(JobAssessmentService.class); profiles = mock(ResumePersistenceService.class);
        preferences = mock(JobSearchPreferenceService.class); jobs = mock(JobService.class);
        service = new ApplicationDecisionService(assessments, profiles, preferences, jobs);
        when(profiles.facts(1L)).thenReturn(facts(CandidateFactState.YES, CandidateFactState.NO));
        when(preferences.data(1L)).thenReturn(preference());
        when(jobs.applicationSnapshot(anyLong())).thenReturn(job("Backend Engineer"));
    }

    @Test void incompatibleJobIsSkipped() {
        stub(MatchResult.Recommendation.NOT_RECOMMENDED, "20");
        assertEquals(ApplicationDecision.SKIP, service.decide(9L, 1L).decision());
    }
    @Test void ordinaryAcceptableJobRoutesVolume() {
        stub(MatchResult.Recommendation.GOOD_MATCH, "72");
        assertEquals(ApplicationDecision.APPLY_VOLUME, service.decide(9L, 1L).decision());
    }
    @Test void stronglyRankedJobRoutesPrecision() {
        stub(MatchResult.Recommendation.STRONG_MATCH, "91");
        assertEquals(ApplicationDecision.APPLY_PRECISION, service.decide(9L, 1L).decision());
    }
    @Test void explicitPrecisionDefaultCanPromoteAnOrdinaryAcceptableJob() {
        stub(MatchResult.Recommendation.GOOD_MATCH, "72");
        when(preferences.data(1L)).thenReturn(new JobSearchPreferenceData(ResumeStrategy.PRECISION,
                List.of("Engineer"), List.of(), List.of("Bengaluru"), Set.of(), null, null, 7));
        var result = service.decide(9L, 1L);
        assertEquals(ApplicationDecision.APPLY_PRECISION, result.decision());
        assertTrue(result.reasons().contains("DEFAULT_PRECISION_STRATEGY"));
    }
    @Test void missingCriticalCandidateFactNeedsUser() {
        stub(MatchResult.Recommendation.GOOD_MATCH, "72");
        when(profiles.facts(1L)).thenReturn(facts(CandidateFactState.UNKNOWN, CandidateFactState.NO));
        var result = service.decide(9L, 1L);
        assertEquals(ApplicationDecision.NEEDS_USER, result.decision());
        assertTrue(result.reasons().contains("WORK_AUTHORIZATION_UNKNOWN"));
    }
    @Test void weakMatchDoesNotDemandFactsThatAreOnlyNeededToApply() {
        stub(MatchResult.Recommendation.WEAK_MATCH, "64.99");
        when(profiles.facts(1L)).thenReturn(facts(CandidateFactState.UNKNOWN, CandidateFactState.UNKNOWN));
        when(preferences.data(1L)).thenReturn(null);

        assertEquals(ApplicationDecision.SAVE, service.decide(9L, 1L).decision());
    }
    @Test void excludedRoleOutranksUnknownCandidateFactsAndStrongMatch() {
        stub(MatchResult.Recommendation.STRONG_MATCH, "20");
        when(profiles.facts(1L)).thenReturn(facts(CandidateFactState.UNKNOWN, CandidateFactState.UNKNOWN));
        when(preferences.data(1L)).thenReturn(new JobSearchPreferenceData(ResumeStrategy.PRECISION,
                List.of(), List.of("Backend Engineer"), List.of(), Set.of(), null, null, null));

        assertEquals(ApplicationDecision.SKIP, service.decide(9L, 1L).decision());
    }
    @Test void excludedRoleUsesPhraseBoundaries() {
        stub(MatchResult.Recommendation.GOOD_MATCH, "72");
        when(jobs.applicationSnapshot(anyLong())).thenReturn(job("Salesforce Engineer"));
        when(preferences.data(1L)).thenReturn(new JobSearchPreferenceData(ResumeStrategy.VOLUME,
                List.of(), List.of("Sales"), List.of(), Set.of(), null, null, null));

        assertEquals(ApplicationDecision.APPLY_VOLUME, service.decide(9L, 1L).decision());
    }
    @Test void recommendationIsTheAuthorityRatherThanAnInconsistentNumericScore() {
        stub(MatchResult.Recommendation.NOT_RECOMMENDED, "99");
        assertEquals(ApplicationDecision.SKIP, service.decide(9L, 1L).decision());
        stub(MatchResult.Recommendation.STRONG_MATCH, "1");
        assertEquals(ApplicationDecision.APPLY_PRECISION, service.decide(9L, 1L).decision());
    }
    @Test void sponsorshipYesIsConservativelyDeferredToReadinessNotInventedAsSupported() {
        stub(MatchResult.Recommendation.GOOD_MATCH, "72");
        when(profiles.facts(1L)).thenReturn(facts(CandidateFactState.YES, CandidateFactState.YES));

        assertEquals(ApplicationDecision.APPLY_VOLUME, service.decide(9L, 1L).decision());
    }

    private void stub(MatchResult.Recommendation recommendation, String score) {
        MatchResponse match = mock(MatchResponse.class);
        when(match.recommendation()).thenReturn(recommendation);
        when(match.overallScore()).thenReturn(new BigDecimal(score));
        when(assessments.assess(9L, 1L)).thenReturn(match);
    }
    private static CandidateProfileFacts facts(CandidateFactState auth, CandidateFactState sponsorship) {
        return new CandidateProfileFacts("Candidate", "c@example.com", null, "Bengaluru", "Engineer", 60,
                auth, sponsorship, CandidateFactState.UNKNOWN, null, "INR", new BigDecimal("100"), null);
    }
    private static JobSearchPreferenceData preference() {
        return new JobSearchPreferenceData(ResumeStrategy.VOLUME, List.of("Engineer"), List.of(),
                List.of("Bengaluru"), Set.of(WorkArrangement.REMOTE), null, null, 7);
    }
    private static JobApplicationSnapshot job(String title) {
        return new JobApplicationSnapshot(new JobMatchingSnapshot(9L, title, "Description", "Bengaluru",
                null, LocalDateTime.now()), "Company", "https://example.test/job", JobStatus.DISCOVERED);
    }
}
