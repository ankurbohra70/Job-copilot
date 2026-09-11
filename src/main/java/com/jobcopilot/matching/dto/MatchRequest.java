package com.jobcopilot.matching.dto;
import jakarta.validation.constraints.*;
public record MatchRequest(@NotNull @Positive Long candidateProfileId, @NotNull @Positive Long jobId) {}

