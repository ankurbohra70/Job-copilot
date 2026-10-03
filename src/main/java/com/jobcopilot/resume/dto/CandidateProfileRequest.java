package com.jobcopilot.resume.dto;

import com.jobcopilot.resume.CandidateFactState;
import com.jobcopilot.resume.ProfessionalLink;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

public record CandidateProfileRequest(
        @Size(max = 255) String fullName,
        @Email @Size(max = 320) String email,
        @Size(max = 64) String phone,
        @Size(max = 255) String location,
        @Size(max = 255) String currentTitle,
        @Min(0) @Max(960) Integer totalRelevantExperienceMonths,
        CandidateFactState workAuthorization,
        CandidateFactState sponsorshipRequired,
        @Min(0) @Max(730) Integer noticePeriodDays,
        @Size(max = 20) java.util.List<@jakarta.validation.Valid ProfessionalLink> professionalLinks) {
    public CandidateProfileRequest(String fullName, String email, String phone, String location, String currentTitle,
            Integer totalRelevantExperienceMonths, CandidateFactState workAuthorization,
            CandidateFactState sponsorshipRequired, Integer noticePeriodDays) {
        this(fullName, email, phone, location, currentTitle, totalRelevantExperienceMonths, workAuthorization,
                sponsorshipRequired, noticePeriodDays, java.util.List.of());
    }
    @Override public String toString() { return "CandidateProfileRequest[redacted]"; }
}
