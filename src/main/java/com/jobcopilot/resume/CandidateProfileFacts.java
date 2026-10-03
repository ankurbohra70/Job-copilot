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
        Integer noticePeriodDays,
        java.util.List<ProfessionalLink> professionalLinks) {
    public CandidateProfileFacts {
        professionalLinks = CandidateFactsValidator.canonicalLinks(professionalLinks);
    }
    public CandidateProfileFacts(String fullName, String email, String phone, String location, String currentTitle,
            Integer totalRelevantExperienceMonths, CandidateFactState workAuthorization,
            CandidateFactState sponsorshipRequired, Integer noticePeriodDays) {
        this(fullName, email, phone, location, currentTitle, totalRelevantExperienceMonths,
                workAuthorization, sponsorshipRequired, noticePeriodDays, java.util.List.of());
    }
    @Override public String toString() { return "CandidateProfileFacts[redacted]"; }
}
