package com.jobcopilot.resume;

import java.time.LocalDate;

/** Extracts resume-supported candidate facts without inferring job-search intent. */
public interface CandidateProfileExtractor {
    CandidateProfileExtraction extract(String text, LocalDate assessedOn);
}
