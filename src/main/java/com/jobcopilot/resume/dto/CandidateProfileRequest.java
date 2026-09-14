package com.jobcopilot.resume.dto;

import com.jobcopilot.resume.CandidateFactState;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record CandidateProfileRequest(
        @Size(max = 255) String fullName,
        @Email @Size(max = 320) String email,
        @Size(max = 64) String phone,
        @Size(max = 255) String location,
        @Size(max = 255) String currentTitle,
        @Min(0) @Max(960) Integer totalRelevantExperienceMonths,
        CandidateFactState workAuthorization,
        CandidateFactState sponsorshipRequired,
        CandidateFactState relocationWilling,
        @Min(0) @Max(730) Integer noticePeriodDays,
        @Size(min = 3, max = 3) String compensationCurrency,
        @DecimalMin("0") @Digits(integer = 12, fraction = 2) BigDecimal minimumCompensation,
        @DecimalMin("0") @Digits(integer = 12, fraction = 2) BigDecimal desiredCompensation) {
}
