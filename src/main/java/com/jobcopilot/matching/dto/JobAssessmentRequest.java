package com.jobcopilot.matching.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record JobAssessmentRequest(
        @NotNull
        @Positive
        Long candidateProfileId
) {}
