package com.jobcopilot.resume;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import java.time.LocalDate;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import com.jobcopilot.application.ResumeStrategy;
import com.jobcopilot.resume.dto.CandidateProfileRequest;
import com.jobcopilot.resume.dto.CandidateProfileConfirmationRequest;
import com.jobcopilot.resume.dto.JobSearchPreferenceRequest;
import com.jobcopilot.resume.dto.ResumeRouteRequest;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest @Testcontainers
class ResumePersistenceIntegrationTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",POSTGRES::getJdbcUrl); r.add("spring.datasource.username",POSTGRES::getUsername); r.add("spring.datasource.password",POSTGRES::getPassword);
    }
    @Autowired ResumePersistenceService service;
    @Autowired ResumeRepository resumes;
    @Autowired CandidateProfileRepository profiles;
    @Autowired JobSearchPreferenceService preferences;
    @Autowired ResumeRouteService routes;
    @Test void jsonRoundTripAndLifecyclePreserveUnknownEvidence() {
        String text = "Experience\nBackend Engineer at Acme\n2020 - 2023\n- Java services\nEducation\nUniversity";
        var data = new DeterministicProfileParser().parse(text,LocalDate.of(2026,9,7));
        var saved = service.save("a.pdf",100,text,1,"test",data,LocalDate.of(2026,9,7),"rules-v1","v1");
        var loaded = service.getProfile(saved.candidateProfile().id());
        assertEquals(data,loaded.profile()); assertNotNull(loaded.createdAt()); assertNotNull(saved.createdAt());
        assertEquals(data, service.matchingSnapshot(loaded.id()).profile()); assertNull(loaded.totalYearsExperience());
        assertThrows(UnsupportedOperationException.class, () -> loaded.profile().skills().add("invented"));
    }
    @Test void profileFailureRollsBackResumeInsert() {
        long count = resumes.count();
        assertThrows(RuntimeException.class, () -> service.save("a.pdf",10,"text",1,"test",null,LocalDate.now(),"rules-v1","v1"));
        assertEquals(count,resumes.count());
    }
    @Test void deletingResumeCanCascadeToItsProfile() {
        var data = new DeterministicProfileParser().parse("Java",LocalDate.now());
        var saved = service.save("a.pdf",10,"Java",1,"test",data,LocalDate.now(),"rules-v1","v1");
        resumes.deleteById(saved.id());
        assertFalse(profiles.existsById(saved.candidateProfile().id()));
    }
    @Test void typedFactsPreferencesAndResumeRoutesRoundTripWithoutDefaults() {
        var manual = service.createProfile(new CandidateProfileRequest(" Candidate ", "candidate@example.com", null,
                "Bengaluru", "Backend Engineer", null, null, null, null));
        assertNull(manual.resumeId());
        assertEquals(CandidateFactState.UNKNOWN, manual.facts().workAuthorization());

        var updated = service.updateProfile(manual.id(), new CandidateProfileRequest("Candidate", "candidate@example.com",
                "+91 1", "Bengaluru", "Backend Engineer", 72, CandidateFactState.YES, CandidateFactState.NO, 30));
        assertNull(service.matchingSnapshot(manual.id()).profile().totalExperienceMonths());
        assertEquals(72, service.facts(manual.id()).totalRelevantExperienceMonths());

        var preference = preferences.replace(manual.id(), new JobSearchPreferenceRequest(ResumeStrategy.VOLUME,
                List.of("Backend Engineer"), List.of("Sales"), List.of("Bengaluru"), Set.of(WorkArrangement.REMOTE),
                null, new BigDecimal("10"), 7, CandidateFactState.YES, "inr",
                new BigDecimal("1000000"), new BigDecimal("1500000")));
        assertEquals(List.of("Backend Engineer"), preference.preference().targetRoles());
        assertEquals("INR", preference.preference().compensationCurrency());

        var parsed = new DeterministicProfileParser().parse("Java backend engineer", LocalDate.now());
        var upload = service.save("stable-v3.pdf", 100, "Java backend engineer", 1, "pdfbox-v1", parsed,
                LocalDate.now(), "rules-v1", "v1");
        var route = routes.create(manual.id(), new ResumeRouteRequest(upload.id(), ResumeStrategy.VOLUME,
                null, true, "Backend stable v3", true));
        assertEquals(upload.id(), route.resumeId());
        assertEquals("stable-v3.pdf", route.sourceFileName());
        var resolved = routes.resolve(manual.id(), ResumeStrategy.VOLUME, "Backend Engineer");
        assertEquals(route.id(), resolved.id());
        assertEquals(route.resumeId(), resolved.resumeId());
        assertEquals(route.variantLabel(), resolved.variantLabel());
    }

    @Test void preferenceReplacementCanonicalizesCaseAndWhitespaceDuplicates() {
        var profile = service.createProfile(new CandidateProfileRequest("Candidate", "candidate@example.com", null,
                null, null, null, CandidateFactState.UNKNOWN, CandidateFactState.UNKNOWN, null));
        var preference = preferences.replace(profile.id(), new JobSearchPreferenceRequest(ResumeStrategy.VOLUME,
                List.of(" Backend Engineer ", "backend engineer"), List.of(),
                List.of(" Bengaluru ", "bengaluru"), Set.of(), null, null, null));

        assertEquals(List.of("Backend Engineer"), preference.preference().targetRoles());
        assertEquals(List.of("Bengaluru"), preference.preference().preferredLocations());
        var replaced = preferences.replace(profile.id(), new JobSearchPreferenceRequest(ResumeStrategy.PRECISION,
                List.of(), List.of("Sales"), List.of(), Set.of(WorkArrangement.REMOTE), null, null, 30));
        assertTrue(replaced.preference().targetRoles().isEmpty());
        assertTrue(replaced.preference().preferredLocations().isEmpty());
        assertEquals(List.of("Sales"), replaced.preference().excludedRoles());
        assertEquals(CandidateFactState.UNKNOWN, service.getProfile(profile.id()).facts().workAuthorization());
    }

    @Test void conflictingPreferenceRolesAreRejectedWithoutChangingCandidateTruth() {
        var profile = service.createProfile(new CandidateProfileRequest("Candidate", "candidate@example.com", null,
                null, null, null, CandidateFactState.YES, CandidateFactState.NO, null));
        assertThrows(IllegalArgumentException.class, () -> preferences.replace(profile.id(),
                new JobSearchPreferenceRequest(ResumeStrategy.VOLUME, List.of(" Engineer "),
                        List.of("engineer"), List.of(), Set.of(), null, null, null)));
        assertEquals(CandidateFactState.YES, service.getProfile(profile.id()).facts().workAuthorization());
    }

    @Test void roleFamilyMatchingUsesBoundariesAndFallsBackToDefault() {
        var profile = service.createProfile(new CandidateProfileRequest("Candidate", "candidate@example.com", null,
                null, null, null, CandidateFactState.YES, CandidateFactState.NO, null));
        var parsed = new DeterministicProfileParser().parse("Java JavaScript", LocalDate.now());
        var specializedResume = service.save("java.pdf", 100, "Java", 1, "pdfbox-v1", parsed,
                LocalDate.now(), "rules-v1", "v1");
        var defaultResume = service.save("default.pdf", 100, "JavaScript", 1, "pdfbox-v1", parsed,
                LocalDate.now(), "rules-v1", "v1");
        routes.create(profile.id(), new ResumeRouteRequest(specializedResume.id(), ResumeStrategy.VOLUME,
                "Java", false, "Java specialist", true));
        var fallback = routes.create(profile.id(), new ResumeRouteRequest(defaultResume.id(), ResumeStrategy.VOLUME,
                null, true, "Default", true));

        assertEquals(fallback.id(), routes.resolve(profile.id(), ResumeStrategy.VOLUME, "JavaScript Engineer").id());
    }

    @Test void concurrentDefaultRouteCreationLeavesOneRouteAndOneStableConflict() throws Exception {
        var profile = service.createProfile(new CandidateProfileRequest("Candidate", "candidate@example.com", null,
                null, null, null, CandidateFactState.YES, CandidateFactState.NO, null));
        var parsed = new DeterministicProfileParser().parse("Java", LocalDate.now());
        var resume = service.save("stable.pdf", 100, "Java", 1, "pdfbox-v1", parsed,
                LocalDate.now(), "rules-v1", "v1");
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var attempts = List.of("A", "B").stream().map(label -> executor.submit(() -> {
                start.await();
                try {
                    return (Object) routes.create(profile.id(), new ResumeRouteRequest(resume.id(),
                            ResumeStrategy.VOLUME, null, true, label, true));
                } catch (RuntimeException failure) {
                    return failure;
                }
            })).toList();
            start.countDown();
            var outcomes = attempts.stream().map(future -> {
                try { return future.get(); } catch (Exception failure) { throw new AssertionError(failure); }
            }).toList();
            assertEquals(1, outcomes.stream().filter(ResumeRouteSnapshot.class::isInstance).count());
            assertEquals(1, outcomes.stream().filter(ResumeExceptions.ResumeRouteConflictException.class::isInstance).count());
        }
        assertEquals(1, routes.list(profile.id()).size());
    }

    @Test void routeCannotApproveAnObviouslyUnusableLegacyResumeRecord() {
        var profile = service.createProfile(new CandidateProfileRequest("Candidate", "candidate@example.com", null,
                null, null, null, CandidateFactState.YES, CandidateFactState.NO, null));
        Resume unusable = resumes.saveAndFlush(new Resume("legacy.pdf", 100, "", 1, "pdfbox-v1"));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> routes.create(profile.id(), new ResumeRouteRequest(unusable.id(), ResumeStrategy.VOLUME,
                        null, true, "Legacy", true)));
        assertEquals("resume is not a usable validated PDF record", failure.getMessage());
    }

    @Test void unapprovedAppendOnlyRouteDoesNotBlockAResultingApprovedRoute() {
        var profile = service.createProfile(new CandidateProfileRequest("Candidate", "candidate@example.com", null,
                null, null, null, CandidateFactState.YES, CandidateFactState.NO, null));
        var parsed = new DeterministicProfileParser().parse("Java", LocalDate.now());
        var draftResume = service.save("draft.pdf", 100, "Java", 1, "pdfbox-v1", parsed,
                LocalDate.now(), "rules-v1", "v1");
        var approvedResume = service.save("approved.pdf", 100, "Java", 1, "pdfbox-v1", parsed,
                LocalDate.now(), "rules-v1", "v1");
        routes.create(profile.id(), new ResumeRouteRequest(draftResume.id(), ResumeStrategy.PRECISION,
                null, true, "Draft", false));
        assertNull(routes.resolve(profile.id(), ResumeStrategy.PRECISION, "Engineer"));
        var approved = routes.create(profile.id(), new ResumeRouteRequest(approvedResume.id(),
                ResumeStrategy.PRECISION, null, true, "Approved", true));

        assertEquals(approved.id(), routes.resolve(profile.id(), ResumeStrategy.PRECISION, "Engineer").id());
        assertEquals(2, routes.list(profile.id()).size());
    }

    @Test void draftConfirmationCorrectionNoOpAndPreferenceAuthorityAreIndependent() {
        var data = new DeterministicProfileParser().parse("Java backend engineer", LocalDate.now());
        var saved = service.saveDraft("candidate.pdf", 100, "Java backend engineer", 1, "pdfbox-v1",
                data, LocalDate.now(), "rules-v1", "v1");
        long profileId = saved.candidateProfile().id();
        assertEquals(CandidateProfileStatus.DRAFT, saved.candidateProfile().status());
        assertEquals(1, saved.candidateProfile().revision());
        assertThrows(ResumeExceptions.PreferenceNotFoundException.class, () -> preferences.get(profileId));
        assertThrows(ResumeExceptions.CandidateProfileNotConfirmedException.class,
                () -> service.matchingSnapshot(profileId));

        preferences.replace(profileId, new JobSearchPreferenceRequest(ResumeStrategy.VOLUME,
                List.of("Backend Engineer"), List.of(), List.of("Bengaluru"), Set.of(),
                null, null, 7, CandidateFactState.YES, "INR", new BigDecimal("100"), null));
        CandidateProfileRequest facts = new CandidateProfileRequest(" Candidate ", "candidate@example.com",
                null, "Bengaluru", "Backend Engineer", 60, CandidateFactState.YES,
                CandidateFactState.NO, 30);
        var confirmed = service.confirm(profileId,
                new CandidateProfileConfirmationRequest(1, data, facts));
        assertEquals(CandidateProfileStatus.CONFIRMED, confirmed.status());
        assertEquals(2, confirmed.revision());
        assertNotNull(confirmed.confirmedAt());
        assertEquals("Candidate", confirmed.facts().fullName());
        assertDoesNotThrow(() -> service.matchingSnapshot(profileId));

        CandidateProfileData semanticallyEquivalent = new CandidateProfileData(data.skills(),
                data.totalExperienceMonths(), data.observedExperienceMonths(), data.experienceAssessment(),
                data.workExperience(), data.education(), data.projects(),
                data.keywords().stream().map(String::toUpperCase).toList(),
                data.roleCategories().stream().map(String::toUpperCase).toList(),
                data.evidence(), data.warnings());
        var noOp = service.confirm(profileId,
                new CandidateProfileConfirmationRequest(2, semanticallyEquivalent, facts));
        assertEquals(2, noOp.revision());
        assertEquals(List.of("Backend Engineer"), preferences.get(profileId).preference().targetRoles());

        CandidateProfileData corrected = new CandidateProfileData(List.of("Java", "Kotlin"),
                data.totalExperienceMonths(), data.observedExperienceMonths(), data.experienceAssessment(),
                data.workExperience(), data.education(), data.projects(), data.keywords(),
                data.roleCategories(), data.evidence(), data.warnings());
        var updated = service.confirm(profileId,
                new CandidateProfileConfirmationRequest(2, corrected, facts));
        assertEquals(3, updated.revision());
        assertEquals(List.of("java", "kotlin"), updated.profile().skills());
        assertEquals(List.of("Backend Engineer"), preferences.get(profileId).preference().targetRoles());
    }

    @Test void concurrentSameRevisionConfirmationHasExactlyOneWinner() throws Exception {
        var data = new DeterministicProfileParser().parse("Java", LocalDate.now());
        var saved = service.saveDraft("candidate.pdf", 100, "Java", 1, "pdfbox-v1",
                data, LocalDate.now(), "rules-v1", "v1");
        long profileId = saved.candidateProfile().id();
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var futures = List.of("One", "Two").stream().map(title -> executor.submit(() -> {
                start.await();
                try {
                    return (Object) service.confirm(profileId, new CandidateProfileConfirmationRequest(1, data,
                            new CandidateProfileRequest("Candidate", "candidate@example.com", null, null,
                                    title, null, CandidateFactState.YES, CandidateFactState.NO, null)));
                } catch (RuntimeException failure) {
                    return failure;
                }
            })).toList();
            start.countDown();
            var outcomes = futures.stream().map(future -> {
                try { return future.get(); } catch (Exception failure) { throw new AssertionError(failure); }
            }).toList();
            assertEquals(1, outcomes.stream().filter(com.jobcopilot.resume.dto.CandidateProfileResponse.class::isInstance).count());
            assertEquals(1, outcomes.stream()
                    .filter(ResumeExceptions.CandidateProfileRevisionConflictException.class::isInstance).count());
        }
        assertEquals(2, service.getProfile(profileId).revision());
    }
}

