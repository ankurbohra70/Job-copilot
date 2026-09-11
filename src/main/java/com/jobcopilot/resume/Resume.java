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
    @Column(nullable = false, updatable = false) private LocalDateTime createdAt;
    protected Resume() {}
    Resume(String name, long size, String text, int pages, String version) {
        fileName = name; sizeBytes = size; mediaType = "application/pdf"; extractedText = text;
        pageCount = pages; extractorVersion = version;
    }
    @PrePersist void onCreate() { createdAt = LocalDateTime.now(); }
    Long id() { return id; }
    String fileName() { return fileName; }
    long sizeBytes() { return sizeBytes; }
    String text() { return extractedText; }
    int pageCount() { return pageCount; }
    String extractorVersion() { return extractorVersion; }
    LocalDateTime createdAt() { return createdAt; }
}

