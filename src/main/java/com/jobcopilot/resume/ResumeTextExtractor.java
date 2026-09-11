package com.jobcopilot.resume;

public interface ResumeTextExtractor {
    record Extraction(String text, int pageCount, String version) {}
    Extraction extract(byte[] pdf);
}

