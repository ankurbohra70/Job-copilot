package com.jobcopilot.application;

import com.jobcopilot.discovery.*;
import com.jobcopilot.job.*;
import com.jobcopilot.matching.*;
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
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

class ApplicationReadinessServiceTest {
    private JobService jobs; private JobVerificationService verification; private JobAssessmentService assessments;
    private ApplicationDecisionService decisions; private ResumePersistenceService profiles;
    private JobSearchPreferenceService preferences; private ResumeRouteService routes;
    private ApplicationReadinessService service;

    @BeforeEach void setup() {
        jobs = mock(JobService.class); verification = mock(JobVerificationService.class);
        assessments = mock(JobAssessmentService.class); decisions = mock(ApplicationDecisionService.class);
        profiles = mock(ResumePersistenceService.class); preferences = mock(JobSearchPreferenceService.class);
        routes = mock(ResumeRouteService.class);
        service = new ApplicationReadinessService(jobs, verification, assessments, decisions, profiles, preferences, routes);
        when(jobs.applicationSnapshot(9L)).thenReturn(job(JobStatus.DISCOVERED));
        when(verification.find(9L)).thenReturn(verified(ListingAvailability.LIVE, "https://apply.example.test/9"));
        MatchResponse assessed = match(MatchResult.Status.MEETS_REQUIREMENT);
        when(assessments.assess(9L, 1L)).thenReturn(assessed);
        when(profiles.facts(1L)).thenReturn(facts(CandidateFactState.YES));
        when(preferences.data(1L)).thenReturn(preference());
        when(decisions.decide(eq(9L), eq(1L), any())).thenReturn(decision(ApplicationDecision.APPLY_VOLUME));
        when(routes.resolve(1L, ResumeStrategy.VOLUME, "Backend Engineer")).thenReturn(route());
    }

    @Test void closedJobIsNotReady() {
        when(verification.find(9L)).thenReturn(verified(ListingAvailability.CLOSED, "https://apply.example.test/9"));
        assertEquals(ApplicationReadiness.NOT_READY, service.evaluate(9L, 1L).readiness());
    }
    @Test void missingApplicationUrlIsNotReady() {
        when(verification.find(9L)).thenReturn(verified(ListingAvailability.LIVE, null));
        assertEquals(ApplicationReadiness.NOT_READY, service.evaluate(9L, 1L).readiness());
    }
    @Test void opaqueHttpsUriIsNotAUsableApplicationUrl() {
        when(verification.find(9L)).thenReturn(verified(ListingAvailability.LIVE, "https:apply.example.test/9"));
        var result = service.evaluate(9L, 1L);
        assertEquals(ApplicationReadiness.NOT_READY, result.readiness());
        assertEquals(ReadinessCheck.Status.FAIL, status(result, "APPLICATION_URL"));
    }
    @Test void verificationOlderThanConfiguredFreshnessIsNotReady() {
        when(verification.find(9L)).thenReturn(new JobVerificationSnapshot(3L, ListingAvailability.LIVE, null,
                "https://apply.example.test/9", ExtractionState.CURRENT, LocalDateTime.now().minusDays(8)));
        var result = service.evaluate(9L, 1L);
        assertEquals(ApplicationReadiness.NOT_READY, result.readiness());
        assertEquals(ReadinessCheck.Status.FAIL, status(result, "VERIFICATION_FRESHNESS"));
    }
    @Test void locationSubstringCollisionCannotProduceReady() {
        when(jobs.applicationSnapshot(9L)).thenReturn(new JobApplicationSnapshot(
                new JobMatchingSnapshot(9L, "Backend Engineer", "Description", "Indiana", null, LocalDateTime.now()),
                "Company", null, JobStatus.DISCOVERED));
        when(preferences.data(1L)).thenReturn(new JobSearchPreferenceData(ResumeStrategy.VOLUME,
                List.of(), List.of(), List.of("India"), Set.of(), null, null, 7));
        var result = service.evaluate(9L, 1L);
        assertEquals(ApplicationReadiness.NOT_READY, result.readiness());
        assertEquals(ReadinessCheck.Status.FAIL, status(result, "LOCATION_COMPATIBILITY"));
    }
    @Test void missingRequiredIdentityNeedsUser() {
        CandidateProfileFacts incomplete = new CandidateProfileFacts(null, null, null, "Bengaluru", "Engineer", 60,
                CandidateFactState.YES, CandidateFactState.NO, null);
        when(profiles.facts(1L)).thenReturn(incomplete);
        var result = service.evaluate(9L, 1L);
        assertEquals(ApplicationReadiness.NEEDS_USER, result.readiness());
        assertEquals(ReadinessCheck.Status.NEEDS_USER, status(result, "CANDIDATE_IDENTITY"));
        assertEquals(ReadinessCheck.Status.NEEDS_USER, status(result, "CANDIDATE_EMAIL"));
    }
    @Test void unknownCompensationDoesNotBlockWhenJobDoesNotRequireIt() {
        CandidateProfileFacts withoutCompensation = new CandidateProfileFacts("Candidate", "c@example.com", null,
                "Bengaluru", "Engineer", 60, CandidateFactState.YES, CandidateFactState.NO,
                null);
        when(profiles.facts(1L)).thenReturn(withoutCompensation);
        var result = service.evaluate(9L, 1L);
        assertEquals(ApplicationReadiness.READY, result.readiness());
        assertEquals(ReadinessCheck.Status.NOT_APPLICABLE, status(result, "COMPENSATION"));
    }
    @Test void hardFailureOutranksCandidateOwnedUnknown() {
        when(verification.find(9L)).thenReturn(verified(ListingAvailability.CLOSED, null));
        when(profiles.facts(1L)).thenReturn(facts(CandidateFactState.UNKNOWN));
        when(decisions.decide(eq(9L), eq(1L), any())).thenReturn(decision(ApplicationDecision.NEEDS_USER));
        assertEquals(ApplicationReadiness.NOT_READY, service.evaluate(9L, 1L).readiness());
    }
    @Test void multiQueryReadinessUsesOneReadOnlyRepeatableSnapshot() throws Exception {
        Transactional transaction = ApplicationReadinessService.class
                .getMethod("evaluate", Long.class, Long.class).getAnnotation(Transactional.class);
        assertTrue(transaction.readOnly());
        assertEquals(Isolation.REPEATABLE_READ, transaction.isolation());
    }
    @Test void unknownCriticalFactNeedsUser() {
        when(profiles.facts(1L)).thenReturn(facts(CandidateFactState.UNKNOWN));
        when(decisions.decide(eq(9L), eq(1L), any())).thenReturn(decision(ApplicationDecision.NEEDS_USER));
        var result = service.evaluate(9L, 1L);
        assertEquals(ApplicationReadiness.NEEDS_USER, result.readiness());
        assertEquals(ReadinessCheck.Status.NEEDS_USER, status(result, "WORK_AUTHORIZATION"));
    }
    @Test void explicitAuthorizationNoIsNotReady() {
        when(profiles.facts(1L)).thenReturn(facts(CandidateFactState.NO));
        assertEquals(ApplicationReadiness.NOT_READY, service.evaluate(9L, 1L).readiness());
    }
    @Test void sponsorshipRequirementNeedsUserBecauseJobSupportIsUnknown() {
        CandidateProfileFacts sponsorshipRequired = new CandidateProfileFacts("Candidate", "c@example.com", null,
                "Bengaluru", "Engineer", 60, CandidateFactState.YES, CandidateFactState.YES,
                null);
        when(profiles.facts(1L)).thenReturn(sponsorshipRequired);
        assertEquals(ApplicationReadiness.NEEDS_USER, service.evaluate(9L, 1L).readiness());
    }
    @Test void staleExtractionIsNotReady() {
        when(verification.find(9L)).thenReturn(new JobVerificationSnapshot(3L, ListingAvailability.LIVE, null,
                "https://apply.example.test/9", ExtractionState.STALE, LocalDateTime.now()));
        assertEquals(ApplicationReadiness.NOT_READY, service.evaluate(9L, 1L).readiness());
    }
    @Test void skipAndSaveDecisionsAreNotReady() {
        for (ApplicationDecision unsuitable : List.of(ApplicationDecision.SKIP, ApplicationDecision.SAVE)) {
            when(decisions.decide(eq(9L), eq(1L), any())).thenReturn(decision(unsuitable));
            assertEquals(ApplicationReadiness.NOT_READY, service.evaluate(9L, 1L).readiness());
        }
    }
    @Test void completeCompatibleCaseIsReadyAndStructured() {
        var result = service.evaluate(9L, 1L);
        assertEquals(ApplicationReadiness.READY, result.readiness());
        assertEquals(ReadinessCheck.Status.PASS, status(result, "JOB_VERIFIED_LIVE"));
        assertEquals(ReadinessCheck.Status.PASS, status(result, "RESUME_ROUTE"));
        verify(jobs, never()).updateJobStatus(anyLong(), any());
    }
    @Test void missingResumeRouteIsNotReady() {
        when(routes.resolve(1L, ResumeStrategy.VOLUME, "Backend Engineer")).thenReturn(null);
        var result = service.evaluate(9L, 1L);
        assertEquals(ApplicationReadiness.NOT_READY, result.readiness());
        assertEquals(ReadinessCheck.Status.FAIL, status(result, "RESUME_ROUTE"));
    }
    @Test void unavailableAssessmentBecomesStructuredNotReadyResult() {
        when(assessments.assess(9L, 1L)).thenThrow(new MatchCannotBeComputedException());
        var result = service.evaluate(9L, 1L);
        assertEquals(ApplicationReadiness.NOT_READY, result.readiness());
        assertEquals(ReadinessCheck.Status.FAIL, status(result, "ASSESSMENT"));
        assertEquals(ReadinessCheck.Status.NOT_APPLICABLE, status(result, "REQUIRED_SKILLS"));
    }

    private static ReadinessCheck.Status status(ApplicationReadinessResult result, String code) {
        return result.checks().stream().filter(c -> c.code().equals(code)).findFirst().orElseThrow().status();
    }
    private static JobApplicationSnapshot job(JobStatus status) {
        return new JobApplicationSnapshot(new JobMatchingSnapshot(9L, "Backend Engineer", "Description", "Bengaluru",
                null, LocalDateTime.now()), "Company", null, status);
    }
    private static JobVerificationSnapshot verified(ListingAvailability availability, String url) {
        return new JobVerificationSnapshot(3L, availability, null, url, ExtractionState.CURRENT, LocalDateTime.now());
    }
    private static MatchResponse match(MatchResult.Status experience) {
        MatchResponse response = mock(MatchResponse.class);
        when(response.overallScore()).thenReturn(new BigDecimal("72"));
        when(response.recommendation()).thenReturn(MatchResult.Recommendation.GOOD_MATCH);
        when(response.experienceComparison()).thenReturn(new MatchResult.ExperienceComparison(
                new BigDecimal("3"), new BigDecimal("5"), experience, new BigDecimal("100")));
        when(response.missingRequiredSkills()).thenReturn(List.of());
        return response;
    }
    private static CandidateProfileFacts facts(CandidateFactState auth) {
        return new CandidateProfileFacts("Candidate", "c@example.com", null, "Bengaluru", "Engineer", 60,
                auth, CandidateFactState.NO, null);
    }
    private static JobSearchPreferenceData preference() {
        return new JobSearchPreferenceData(ResumeStrategy.VOLUME, List.of("Engineer"), List.of(),
                List.of("Bengaluru"), Set.of(), null, null, 7);
    }
    private static ApplicationDecisionResult decision(ApplicationDecision decision) {
        ResumeStrategy strategy = decision == ApplicationDecision.APPLY_VOLUME ? ResumeStrategy.VOLUME : null;
        return new ApplicationDecisionResult(9L, 1L, decision, List.of("TEST"), new BigDecimal("72"),
                MatchResult.Recommendation.GOOD_MATCH, decision == ApplicationDecision.NEEDS_USER, strategy,
                ApplicationDecisionPolicy.VERSION);
    }
    private static ResumeRouteSnapshot route() {
        return new ResumeRouteSnapshot(5L, 1L, 2L, ResumeStrategy.VOLUME, null, true,
                "Backend stable", true, "resume.pdf", "pdfbox-v1", LocalDateTime.now());
    }
}
