package com.jobcopilot.resume;

import java.time.LocalDate;

public interface CandidateFactsExtractor {
    CandidateFactExtraction extract(ResumeDocument document, CandidateProfileData canonicalProfile, LocalDate assessedOn);
}
