package com.jobcopilot.resume;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity @Table(name = "resumes")
class Resume {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, length = 255) private String fileName;
    @Column(nullable = false) private long sizeBytes;
    @Column(nullable = false, length = 100) private String mediaType;
    @Column(nullable = false, columnDefinition = "TEXT") private String extractedText;
    @Column(nullable = false) private int pageCount;
    @Column(nullable = false, length = 64) private String extractorVersion;
    @Column(length = 32) private String layoutVersion;
    @Column(nullable = false, updatable = false) private LocalDateTime createdAt;
    protected Resume() {}
    Resume(String name, long size, String text, int pages, String version) {
        fileName = name; sizeBytes = size; mediaType = "application/pdf"; extractedText = text;
        pageCount = pages; extractorVersion = version;
    }
    @PrePersist void onCreate() { createdAt = LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS); }
    Long id() { return id; }
    String fileName() { return fileName; }
    long sizeBytes() { return sizeBytes; }
    String text() { return extractedText; }
    int pageCount() { return pageCount; }
    String extractorVersion() { return extractorVersion; }
    String layoutVersion() { return layoutVersion; }
    void recordLayoutVersion(String version) { layoutVersion = version; }
    LocalDateTime createdAt() { return createdAt; }
    boolean usableRouteSource() {
        return fileName != null && !fileName.isBlank() && fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".pdf")
                && "application/pdf".equalsIgnoreCase(mediaType) && sizeBytes > 0 && pageCount > 0
                && extractedText != null && !extractedText.isBlank()
                && extractorVersion != null && !extractorVersion.isBlank();
    }
}

