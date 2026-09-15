package com.jobcopilot.resume;

import com.jobcopilot.application.ResumeStrategy;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import com.jobcopilot.resume.CandidateFactState;

public record JobSearchPreferenceData(
        ResumeStrategy defaultResumeStrategy,
        List<String> targetRoles,
        List<String> excludedRoles,
        List<String> preferredLocations,
        Set<WorkArrangement> acceptableWorkArrangements,
        BigDecimal minimumExperienceToleranceYears,
        BigDecimal maximumExperienceToleranceYears,
        Integer freshnessDays,
        CandidateFactState relocationWilling,
        String compensationCurrency,
        BigDecimal minimumCompensation,
        BigDecimal desiredCompensation) {
    public JobSearchPreferenceData {
        targetRoles = targetRoles == null ? List.of() : List.copyOf(targetRoles);
        excludedRoles = excludedRoles == null ? List.of() : List.copyOf(excludedRoles);
        preferredLocations = preferredLocations == null ? List.of() : List.copyOf(preferredLocations);
        acceptableWorkArrangements = acceptableWorkArrangements == null ? Set.of() : Set.copyOf(acceptableWorkArrangements);
        relocationWilling = relocationWilling == null ? CandidateFactState.UNKNOWN : relocationWilling;
    }
    public JobSearchPreferenceData(ResumeStrategy strategy, List<String> targets, List<String> excluded,
            List<String> locations, Set<WorkArrangement> arrangements, BigDecimal minimumTolerance,
            BigDecimal maximumTolerance, Integer freshnessDays) {
        this(strategy, targets, excluded, locations, arrangements, minimumTolerance, maximumTolerance,
                freshnessDays, CandidateFactState.UNKNOWN, null, null, null);
    }
}
