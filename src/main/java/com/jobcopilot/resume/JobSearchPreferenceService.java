package com.jobcopilot.resume;

import com.jobcopilot.resume.dto.JobSearchPreferenceRequest;
import com.jobcopilot.resume.dto.JobSearchPreferenceResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.jobcopilot.resume.ResumeExceptions.*;

@Service
public class JobSearchPreferenceService {
    private final CandidateProfileRepository profiles;
    private final JobSearchPreferenceRepository preferences;
    JobSearchPreferenceService(CandidateProfileRepository profiles, JobSearchPreferenceRepository preferences) {
        this.profiles = profiles; this.preferences = preferences;
    }
    @Transactional
    public JobSearchPreferenceResponse replace(Long profileId, JobSearchPreferenceRequest request) {
        CandidateProfile profile = profiles.findById(profileId).orElseThrow(() -> new CandidateProfileNotFoundException(profileId));
        JobSearchPreference preference = preferences.findByCandidateProfileId(profileId).orElseGet(() -> new JobSearchPreference(profile));
        preference.replace(new JobSearchPreferenceData(request.defaultResumeStrategy(), request.targetRoles(),
                request.excludedRoles(), request.preferredLocations(), request.acceptableWorkArrangements(),
                request.minimumExperienceToleranceYears(), request.maximumExperienceToleranceYears(), request.freshnessDays(),
                request.relocationWilling(), request.compensationCurrency(),
                request.minimumCompensation(), request.desiredCompensation()));
        return response(profileId, preferences.saveAndFlush(preference));
    }
    @Transactional(readOnly = true)
    public JobSearchPreferenceResponse get(Long profileId) {
        if (!profiles.existsById(profileId)) throw new CandidateProfileNotFoundException(profileId);
        return response(profileId, preferences.findByCandidateProfileId(profileId)
                .orElseThrow(() -> new PreferenceNotFoundException(profileId)));
    }
    @Transactional(readOnly = true)
    public JobSearchPreferenceResponse find(Long profileId) {
        return preferences.findByCandidateProfileId(profileId).map(value -> response(profileId, value)).orElse(null);
    }
    @Transactional(readOnly = true)
    public JobSearchPreferenceData data(Long profileId) {
        return preferences.findByCandidateProfileId(profileId).map(JobSearchPreference::data).orElse(null);
    }
    private static JobSearchPreferenceResponse response(Long profileId, JobSearchPreference p) {
        return new JobSearchPreferenceResponse(p.id(), profileId, p.data(), p.createdAt(), p.updatedAt());
    }
}
