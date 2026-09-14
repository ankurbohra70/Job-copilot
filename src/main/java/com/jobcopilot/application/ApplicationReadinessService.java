package com.jobcopilot.application;

import com.jobcopilot.discovery.JobVerificationService;
import com.jobcopilot.discovery.ListingAvailability;
import com.jobcopilot.discovery.ExtractionState;
import com.jobcopilot.job.JobApplicationSnapshot;
import com.jobcopilot.job.JobService;
import com.jobcopilot.job.JobStatus;
import com.jobcopilot.matching.JobAssessmentService;
import com.jobcopilot.matching.MatchResult;
import com.jobcopilot.matching.MatchCannotBeComputedException;
import com.jobcopilot.matching.dto.MatchResponse;
import com.jobcopilot.resume.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static com.jobcopilot.application.ReadinessCheck.Status.*;

@Service
public class ApplicationReadinessService {
    private final JobService jobs;
    private final JobVerificationService verification;
    private final JobAssessmentService assessments;
    private final ApplicationDecisionService decisions;
    private final ResumePersistenceService profiles;
    private final JobSearchPreferenceService preferences;
    private final ResumeRouteService routes;

    public ApplicationReadinessService(JobService jobs, JobVerificationService verification,
            JobAssessmentService assessments, ApplicationDecisionService decisions,
            ResumePersistenceService profiles, JobSearchPreferenceService preferences, ResumeRouteService routes) {
        this.jobs = jobs; this.verification = verification; this.assessments = assessments; this.decisions = decisions;
        this.profiles = profiles; this.preferences = preferences; this.routes = routes;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ApplicationReadinessResult evaluate(Long jobId, Long profileId) {
        JobApplicationSnapshot job = jobs.applicationSnapshot(jobId);
        MatchResponse match = null;
        ApplicationDecisionResult decision;
        try {
            match = assessments.assess(jobId, profileId);
            decision = decisions.decide(jobId, profileId, match);
        } catch (MatchCannotBeComputedException unavailable) {
            decision = new ApplicationDecisionResult(jobId, profileId, ApplicationDecision.SAVE,
                    List.of("ASSESSMENT_UNAVAILABLE"), null, null, false, null, ApplicationDecisionPolicy.VERSION);
        }
        CandidateProfileFacts facts = profiles.facts(profileId);
        JobSearchPreferenceData preference = preferences.data(profileId);
        var verified = verification.find(jobId);
        List<ReadinessCheck> checks = new ArrayList<>();

        boolean pursuitState = job.status() == JobStatus.DISCOVERED || job.status() == JobStatus.SHORTLISTED;
        checks.add(check("JOB_PURSUIT_STATE", pursuitState ? PASS : FAIL,
                pursuitState ? "Job lifecycle permits a new application" : "Job lifecycle does not permit a new application"));
        checks.add(check("JOB_VERIFIED_LIVE", verified != null && verified.availability() == ListingAvailability.LIVE ? PASS : FAIL,
                verified == null ? "No verified external listing exists" : "External listing is " + verified.availability()));
        checks.add(verificationFreshnessCheck(verified, preference));
        checks.add(check("REQUIREMENTS_CURRENT", verified != null && verified.extractionState() == ExtractionState.CURRENT ? PASS : FAIL,
                verified == null ? "No listing extraction state is available" : "Requirement extraction is " + verified.extractionState()));
        String applicationUrl = verified == null ? null : verified.applyUrl();
        checks.add(check("APPLICATION_URL", validHttpUrl(applicationUrl) ? PASS : FAIL,
                validHttpUrl(applicationUrl) ? "A valid verified application URL is available" : "A valid verified application URL is unavailable"));
        checks.add(check("ASSESSMENT", match == null ? FAIL : PASS,
                match == null ? "Deterministic assessment cannot be computed" : "Deterministic assessment is available"));
        checks.add(check("RANKING_INPUT", match == null ? FAIL : PASS,
                match == null ? "A ranking input score is unavailable" : "Deterministic score " + match.overallScore() + " is available"));
        checks.add(check("CANDIDATE_PROFILE", PASS, "Candidate profile is available"));
        checks.add(requiredCandidateFact("CANDIDATE_IDENTITY", facts.fullName(), "Candidate name"));
        checks.add(requiredCandidateFact("CANDIDATE_EMAIL", facts.email(), "Candidate email"));

        checks.add(locationCheck(job.matching().location(), preference));
        checks.add(workAuthorizationCheck(facts.workAuthorization()));
        checks.add(sponsorshipCheck(facts.sponsorshipRequired()));
        checks.add(compensationCheck(facts));
        checks.add(match == null ? check("EXPERIENCE_COMPATIBILITY", NOT_APPLICABLE, "Assessment is unavailable")
                : experienceCheck(match.experienceComparison().status()));
        checks.add(match == null ? check("REQUIRED_SKILLS", NOT_APPLICABLE, "Assessment is unavailable")
                : check("REQUIRED_SKILLS", PASS,
                    match.missingRequiredSkills().isEmpty() ? "All recognized required skills are matched"
                            : "Compatibility assessed with " + match.missingRequiredSkills().size() + " recognized required skill gap(s)"));

        ResumeRouteSnapshot route = decision.recommendedResumeStrategy() == null ? null
                : routes.resolve(profileId, decision.recommendedResumeStrategy(), job.matching().title());
        ReadinessCheck.Status routeStatus = decision.recommendedResumeStrategy() == null ? NOT_APPLICABLE
                : route == null ? FAIL : PASS;
        checks.add(check("RESUME_ROUTE", routeStatus, route == null
                ? (decision.recommendedResumeStrategy() == null ? "Decision does not require a resume" : "No approved resume route is resolved")
                : "Resolved approved " + route.strategy() + " route " + route.variantLabel()));

        if (decision.decision() == ApplicationDecision.SKIP || decision.decision() == ApplicationDecision.SAVE)
            checks.add(check("APPLICATION_DECISION", FAIL, "Decision is " + decision.decision()));
        else if (decision.decision() == ApplicationDecision.NEEDS_USER)
            checks.add(check("APPLICATION_DECISION", NEEDS_USER, "Decision requires candidate input"));
        else checks.add(check("APPLICATION_DECISION", PASS, "Decision is " + decision.decision()));

        ApplicationReadiness overall = checks.stream().anyMatch(c -> c.status() == FAIL)
                ? ApplicationReadiness.NOT_READY
                : checks.stream().anyMatch(c -> c.status() == NEEDS_USER)
                    ? ApplicationReadiness.NEEDS_USER : ApplicationReadiness.READY;
        return new ApplicationReadinessResult(jobId, profileId, overall, decision, route, checks);
    }

    private static ReadinessCheck locationCheck(String location, JobSearchPreferenceData preference) {
        if (preference == null) return check("LOCATION_COMPATIBILITY", NEEDS_USER, "Job search preference is missing");
        if (location == null || location.isBlank()) return check("LOCATION_COMPATIBILITY", NEEDS_USER, "Job location is unavailable");
        String normalized = normalize(location);
        List<String> components = Arrays.stream(normalized.split("[,;|()]"))
                .map(ApplicationReadinessService::normalize).filter(v -> !v.isEmpty()).toList();
        if (preference.preferredLocations().stream().map(ApplicationReadinessService::normalize)
                .anyMatch(v -> normalized.equals(v) || components.contains(v)))
            return check("LOCATION_COMPATIBILITY", PASS, "Job location matches a preferred location");
        if (normalized.contains("remote") && preference.acceptableWorkArrangements().contains(WorkArrangement.REMOTE))
            return check("LOCATION_COMPATIBILITY", PASS, "Remote work is acceptable");
        if (normalized.contains("hybrid") && preference.acceptableWorkArrangements().contains(WorkArrangement.HYBRID))
            return check("LOCATION_COMPATIBILITY", PASS, "Hybrid work is acceptable");
        if ((normalized.contains("on-site") || normalized.contains("onsite"))
                && preference.acceptableWorkArrangements().contains(WorkArrangement.ONSITE))
            return check("LOCATION_COMPATIBILITY", PASS, "On-site work is acceptable");
        if (preference.preferredLocations().isEmpty() && preference.acceptableWorkArrangements().isEmpty())
            return check("LOCATION_COMPATIBILITY", NOT_APPLICABLE, "No location policy is configured");
        return check("LOCATION_COMPATIBILITY", FAIL, "Job location does not match configured preferences");
    }
    private static ReadinessCheck workAuthorizationCheck(CandidateFactState state) {
        return switch (state) {
            case YES -> check("WORK_AUTHORIZATION", PASS, "Work authorization is explicitly confirmed");
            case NO -> check("WORK_AUTHORIZATION", FAIL, "Candidate is explicitly not authorized");
            case UNKNOWN -> check("WORK_AUTHORIZATION", NEEDS_USER, "Work authorization is unknown");
        };
    }
    private static ReadinessCheck sponsorshipCheck(CandidateFactState state) {
        return switch (state) {
            case NO -> check("SPONSORSHIP", PASS, "Candidate explicitly does not require sponsorship");
            case YES -> check("SPONSORSHIP", NEEDS_USER, "Job sponsorship support is not represented and must be confirmed");
            case UNKNOWN -> check("SPONSORSHIP", NEEDS_USER, "Sponsorship requirement is unknown");
        };
    }
    private static ReadinessCheck compensationCheck(CandidateProfileFacts facts) {
        boolean known = facts.minimumCompensation() != null || facts.desiredCompensation() != null;
        return check("COMPENSATION", known ? PASS : NOT_APPLICABLE,
                known ? "Compensation expectation is explicitly configured"
                        : "Job-side compensation input requirements are not represented");
    }
    private static ReadinessCheck verificationFreshnessCheck(
            com.jobcopilot.discovery.JobVerificationSnapshot verified, JobSearchPreferenceData preference) {
        if (verified == null || verified.lastVerifiedAt() == null)
            return check("VERIFICATION_FRESHNESS", FAIL, "A verification timestamp is unavailable");
        if (preference == null || preference.freshnessDays() == null)
            return check("VERIFICATION_FRESHNESS", NOT_APPLICABLE, "No verification freshness policy is configured");
        boolean current = !verified.lastVerifiedAt().isBefore(LocalDateTime.now().minusDays(preference.freshnessDays()));
        return check("VERIFICATION_FRESHNESS", current ? PASS : FAIL,
                current ? "Listing verification is within the configured freshness window"
                        : "Listing verification is older than the configured freshness window");
    }
    private static ReadinessCheck requiredCandidateFact(String code, String value, String label) {
        boolean present = value != null && !value.isBlank();
        return check(code, present ? PASS : NEEDS_USER,
                present ? label + " is available" : label + " is required before application");
    }
    private static ReadinessCheck experienceCheck(MatchResult.Status status) {
        return switch (status) {
            case BELOW_REQUIREMENT -> check("EXPERIENCE_COMPATIBILITY", FAIL, "Candidate is below the stated minimum experience");
            case UNKNOWN -> check("EXPERIENCE_COMPATIBILITY", NEEDS_USER, "Experience compatibility is unknown");
            case MEETS_REQUIREMENT, ASSESSED -> check("EXPERIENCE_COMPATIBILITY", PASS, "Experience compatibility is assessed");
            case NOT_APPLICABLE -> check("EXPERIENCE_COMPATIBILITY", NOT_APPLICABLE, "Job has no recognized experience requirement");
        };
    }
    private static ReadinessCheck check(String code, ReadinessCheck.Status status, String message) {
        return new ReadinessCheck(code, status, message);
    }
    private static boolean validHttpUrl(String value) {
        if (value == null) return false;
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            return uri.isAbsolute() && !uri.isOpaque() && uri.getHost() != null && !uri.getHost().isBlank()
                    && uri.getRawUserInfo() == null
                    && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme));
        }
        catch (IllegalArgumentException ignored) { return false; }
    }
    private static String normalize(String value) {
        return value == null ? "" : value.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
