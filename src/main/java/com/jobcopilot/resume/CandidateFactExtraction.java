package com.jobcopilot.resume;

public record CandidateFactExtraction(CandidateProfileFacts facts, String factExtractorVersion,
        CandidateFactExtractionData metadata) {
    public CandidateFactExtraction {
        if (facts == null || factExtractorVersion == null || metadata == null || !facts.equals(metadata.suggestions()))
            throw new IllegalArgumentException("Candidate fact extraction is incomplete");
    }
    @Override public String toString() { return "CandidateFactExtraction[redacted]"; }
}
