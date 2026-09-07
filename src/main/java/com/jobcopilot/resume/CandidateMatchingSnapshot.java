package com.jobcopilot.resume;
import java.time.LocalDate;
public record CandidateMatchingSnapshot(Long id, CandidateProfileData profile, String extractedText,
        String parserVersion, String vocabularyVersion, LocalDate assessedOn) {}

