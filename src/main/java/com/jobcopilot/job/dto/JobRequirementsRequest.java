package com.jobcopilot.job.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.List;

public record JobRequirementsRequest(
        @Size(max = 100) List<@NotBlank @Size(max = 100) String> requiredSkills,
        @Size(max = 100) List<@NotBlank @Size(max = 100) String> preferredSkills,
        @DecimalMin("0") @DecimalMax("80") @Digits(integer = 2, fraction = 2) BigDecimal minYearsExperience) {}

