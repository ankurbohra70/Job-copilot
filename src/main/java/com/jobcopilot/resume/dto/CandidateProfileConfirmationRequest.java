package com.jobcopilot.resume.dto;

import com.jobcopilot.resume.CandidateProfileData;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record CandidateProfileConfirmationRequest(
        @Min(1) long expectedRevision,
        @NotNull CandidateProfileData profile,
        @NotNull @Valid CandidateProfileRequest facts) {
}
