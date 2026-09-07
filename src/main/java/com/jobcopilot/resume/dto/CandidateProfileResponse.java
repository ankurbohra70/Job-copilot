package com.jobcopilot.resume.dto;

import com.jobcopilot.resume.CandidateProfileData;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record CandidateProfileResponse(Long id, Long resumeId, CandidateProfileData profile,
        String schemaVersion, String parserVersion, String vocabularyVersion, LocalDate assessedOn, LocalDateTime createdAt) {
    @com.fasterxml.jackson.annotation.JsonProperty
    public BigDecimal totalYearsExperience() {
        return profile.totalExperienceMonths() == null ? null : BigDecimal.valueOf(profile.totalExperienceMonths())
                .divide(BigDecimal.valueOf(12), 2, RoundingMode.HALF_UP);
    }
}
