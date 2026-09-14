package com.jobcopilot.resume;

import java.math.BigDecimal;

public record CandidateProfileFacts(
        String fullName,
        String email,
        String phone,
        String location,
        String currentTitle,
        Integer totalRelevantExperienceMonths,
        CandidateFactState workAuthorization,
        CandidateFactState sponsorshipRequired,
        CandidateFactState relocationWilling,
        Integer noticePeriodDays,
        String compensationCurrency,
        BigDecimal minimumCompensation,
        BigDecimal desiredCompensation) {
}
