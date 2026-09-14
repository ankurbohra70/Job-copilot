package com.jobcopilot.resume;

import com.jobcopilot.application.ResumeStrategy;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

public record JobSearchPreferenceData(
        ResumeStrategy defaultResumeStrategy,
        List<String> targetRoles,
        List<String> excludedRoles,
        List<String> preferredLocations,
        Set<WorkArrangement> acceptableWorkArrangements,
        BigDecimal minimumExperienceToleranceYears,
        BigDecimal maximumExperienceToleranceYears,
        Integer freshnessDays) {
    public JobSearchPreferenceData {
        targetRoles = targetRoles == null ? List.of() : List.copyOf(targetRoles);
        excludedRoles = excludedRoles == null ? List.of() : List.copyOf(excludedRoles);
        preferredLocations = preferredLocations == null ? List.of() : List.copyOf(preferredLocations);
        acceptableWorkArrangements = acceptableWorkArrangements == null ? Set.of() : Set.copyOf(acceptableWorkArrangements);
    }
}
