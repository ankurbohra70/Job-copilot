package com.jobcopilot.resume.dto;

import com.jobcopilot.application.ResumeStrategy;
import com.jobcopilot.resume.WorkArrangement;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

public record JobSearchPreferenceRequest(
        @NotNull ResumeStrategy defaultResumeStrategy,
        @Size(max = 100) List<@Size(max = 255) String> targetRoles,
        @Size(max = 100) List<@Size(max = 255) String> excludedRoles,
        @Size(max = 100) List<@Size(max = 255) String> preferredLocations,
        Set<WorkArrangement> acceptableWorkArrangements,
        @DecimalMin("0") @DecimalMax("80") @Digits(integer = 2, fraction = 2)
        BigDecimal minimumExperienceToleranceYears,
        @DecimalMin("0") @DecimalMax("80") @Digits(integer = 2, fraction = 2)
        BigDecimal maximumExperienceToleranceYears,
        @Min(1) @Max(365) Integer freshnessDays) {
}
