package com.jobcopilot.resume;

public interface ResumeTextExtractor {
    record Extraction(String text, int pageCount, String version, String layoutVersion, ResumeDocument document) {
        public Extraction(String text, int pageCount, String version) {
            this(text, pageCount, version, null, ResumeDocument.unavailable(text, "LAYOUT_NOT_RECORDED"));
        }
        @Override public String toString() { return "Extraction[redacted]"; }
    }
    Extraction extract(byte[] pdf);
}

