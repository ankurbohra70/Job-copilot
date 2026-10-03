package com.jobcopilot.resume.dto;

import java.time.LocalDateTime;

public record ResumeResponse(Long id, String fileName, long sizeBytes, int pageCount,
        String extractorVersion, LocalDateTime createdAt, CandidateProfileResponse candidateProfile, String layoutVersion) {
    public ResumeResponse(Long id, String fileName, long sizeBytes, int pageCount, String extractorVersion,
            LocalDateTime createdAt, CandidateProfileResponse candidateProfile) {
        this(id, fileName, sizeBytes, pageCount, extractorVersion, createdAt, candidateProfile, null);
    }
    @Override public String toString() { return "ResumeResponse[redacted]"; }
}

