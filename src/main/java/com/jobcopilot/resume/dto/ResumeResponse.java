package com.jobcopilot.resume.dto;

import java.time.LocalDateTime;

public record ResumeResponse(Long id, String fileName, long sizeBytes, int pageCount,
        String extractorVersion, LocalDateTime createdAt, CandidateProfileResponse candidateProfile) {}

