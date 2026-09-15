package com.jobcopilot.resume.dto;

import com.jobcopilot.resume.CandidateProfileData;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import com.jobcopilot.resume.CandidateProfileFacts;
import com.jobcopilot.resume.CandidateProfileStatus;

public record CandidateProfileResponse(Long id, Long resumeId, CandidateProfileData profile,
        CandidateProfileFacts facts, String schemaVersion, String parserVersion, String vocabularyVersion,
        LocalDate assessedOn, CandidateProfileStatus status, long revision, LocalDateTime confirmedAt,
        LocalDateTime createdAt, LocalDateTime updatedAt) {
    @com.fasterxml.jackson.annotation.JsonProperty
    public BigDecimal totalYearsExperience() {
        return profile.totalExperienceMonths() == null ? null : BigDecimal.valueOf(profile.totalExperienceMonths())
                .divide(BigDecimal.valueOf(12), 2, RoundingMode.HALF_UP);
    }
}
