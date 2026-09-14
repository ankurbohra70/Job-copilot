package com.jobcopilot.application;

import com.jobcopilot.matching.JobAssessmentService;
import com.jobcopilot.matching.MatchResult;
import com.jobcopilot.matching.dto.MatchResponse;
import com.jobcopilot.resume.CandidateFactState;
import com.jobcopilot.resume.CandidateProfileFacts;
import com.jobcopilot.resume.JobSearchPreferenceData;
import com.jobcopilot.resume.JobSearchPreferenceService;
import com.jobcopilot.resume.ResumePersistenceService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import com.jobcopilot.job.JobService;

@Service
public class ApplicationDecisionService {
    private final JobAssessmentService assessments;
    private final ResumePersistenceService profiles;
    private final JobSearchPreferenceService preferences;
    private final JobService jobs;
    public ApplicationDecisionService(JobAssessmentService assessments, ResumePersistenceService profiles,
            JobSearchPreferenceService preferences, JobService jobs) {
        this.assessments = assessments; this.profiles = profiles; this.preferences = preferences; this.jobs = jobs;
    }
    @Transactional(readOnly = true)
    public ApplicationDecisionResult decide(Long jobId, Long profileId) {
        return decide(jobId, profileId, assessments.assess(jobId, profileId));
    }
    ApplicationDecisionResult decide(Long jobId, Long profileId, MatchResponse match) {
        CandidateProfileFacts facts = profiles.facts(profileId);
        JobSearchPreferenceData preference = preferences.data(profileId);
        List<String> reasons = new ArrayList<>();
        ApplicationDecision base = ApplicationDecisionPolicy.from(match.recommendation());
        if (base == ApplicationDecision.SKIP) reasons.add("MATCH_NOT_RECOMMENDED");
        if (preference != null && excluded(preference.excludedRoles(), jobs.applicationSnapshot(jobId).matching().title())) {
            base = ApplicationDecision.SKIP; reasons.add("EXCLUDED_ROLE");
        }
        if (base == ApplicationDecision.APPLY_VOLUME && preference != null
                && preference.defaultResumeStrategy() == ResumeStrategy.PRECISION) {
            base = ApplicationDecision.APPLY_PRECISION;
            reasons.add("DEFAULT_PRECISION_STRATEGY");
        }
        boolean missing = facts.workAuthorization() == CandidateFactState.UNKNOWN
                || facts.sponsorshipRequired() == CandidateFactState.UNKNOWN || preference == null;
        if ((base == ApplicationDecision.APPLY_VOLUME || base == ApplicationDecision.APPLY_PRECISION) && missing) {
            base = ApplicationDecision.NEEDS_USER;
            if (facts.workAuthorization() == CandidateFactState.UNKNOWN) reasons.add("WORK_AUTHORIZATION_UNKNOWN");
            if (facts.sponsorshipRequired() == CandidateFactState.UNKNOWN) reasons.add("SPONSORSHIP_UNKNOWN");
            if (preference == null) reasons.add("SEARCH_PREFERENCE_MISSING");
        }
        if (reasons.isEmpty()) reasons.add(switch (base) {
            case APPLY_PRECISION -> "STRONG_MATCH_PRECISION_ROUTE";
            case APPLY_VOLUME -> "GOOD_MATCH_VOLUME_ROUTE";
            case SAVE -> "WEAK_MATCH_SAVE_FOR_REVIEW";
            case SKIP -> "MATCH_NOT_RECOMMENDED";
            case NEEDS_USER -> "CANDIDATE_INPUT_REQUIRED";
        });
        ResumeStrategy strategy = base == ApplicationDecision.APPLY_PRECISION ? ResumeStrategy.PRECISION
                : base == ApplicationDecision.APPLY_VOLUME ? ResumeStrategy.VOLUME : null;
        return new ApplicationDecisionResult(jobId, profileId, base, reasons, match.overallScore(),
                match.recommendation(), missing, strategy, ApplicationDecisionPolicy.VERSION);
    }
    private static boolean excluded(List<String> roles, String title) {
        return roles.stream().anyMatch(role -> Pattern.compile(
                "(?iu)(?<![\\p{L}\\p{N}])" + Pattern.quote(role.strip()) + "(?![\\p{L}\\p{N}])")
                .matcher(title).find());
    }
}
