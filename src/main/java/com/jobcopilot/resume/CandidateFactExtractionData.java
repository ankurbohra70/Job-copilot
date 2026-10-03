package com.jobcopilot.resume;

import java.util.List;

/** Immutable original suggestions, not evidence for later user corrections. */
public record CandidateFactExtractionData(String schemaVersion, CandidateProfileFacts suggestions,
        List<FactEvidence> evidence, List<String> diagnostics) {
    public enum Confidence { HIGH, MEDIUM }
    public enum Derivation { HEADER_TEXT, PDF_URI_ANNOTATION, STRUCTURED_WORK }
    public record FactEvidence(String field, String suggestedValue, String rawValue, Confidence confidence,
            Derivation derivation, String sourceText, Integer page, String lineReference,
            ResumeDocument.Bounds bounds, Integer workReference) {
        public FactEvidence {
            if (field == null || field.length() > 64 || suggestedValue == null || suggestedValue.length() > 2048
                    || rawValue != null && rawValue.length() > 2048 || confidence == null || derivation == null
                    || sourceText == null || sourceText.length() > 512
                    || lineReference != null && lineReference.length() > 64
                    || bounds != null && !bounds.valid())
                throw new IllegalArgumentException("Invalid candidate fact evidence");
        }
        @Override public String toString() { return "FactEvidence[redacted]"; }
    }
    public CandidateFactExtractionData {
        evidence = List.copyOf(evidence); diagnostics = List.copyOf(diagnostics);
        if (!"v1".equals(schemaVersion) || suggestions == null || evidence.size() > 50 || diagnostics.size() > 50
                || evidence.stream().mapToInt(e -> e.sourceText().length()).sum() > 16000
                || diagnostics.stream().anyMatch(d -> d == null || !d.matches("[A-Z0-9_]{1,64}")))
            throw new IllegalArgumentException("Invalid candidate fact extraction metadata");
    }
    @Override public String toString() { return "CandidateFactExtractionData[redacted]"; }
}
