package com.jobcopilot.resume;

public record CandidateProfileExtraction(
        CandidateProfileData profile,
        String extractorVersion,
        String vocabularyVersion) {
    public CandidateProfileExtraction {
        if (profile == null) throw new IllegalArgumentException("Extracted candidate profile is required");
        if (extractorVersion == null || extractorVersion.isBlank())
            throw new IllegalArgumentException("Candidate extractor version is required");
        if (vocabularyVersion == null || vocabularyVersion.isBlank())
            throw new IllegalArgumentException("Candidate vocabulary version is required");
    }
}
