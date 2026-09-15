package com.jobcopilot.resume;

public record CandidateProfileFacts(
        String fullName,
        String email,
        String phone,
        String location,
        String currentTitle,
        Integer totalRelevantExperienceMonths,
        CandidateFactState workAuthorization,
        CandidateFactState sponsorshipRequired,
        Integer noticePeriodDays) {
}
