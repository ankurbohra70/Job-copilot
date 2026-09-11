package com.jobcopilot.resume;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity @Table(name = "candidate_profiles")
class CandidateProfile {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @OneToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "resume_id", nullable = false, unique = true)
    private Resume resume;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb") private CandidateProfileData profileData;
    @Column(nullable = false, length = 32) private String schemaVersion;
    @Column(nullable = false, length = 64) private String parserVersion;
    @Column(nullable = false, length = 32) private String vocabularyVersion;
    @Column(nullable = false) private LocalDate assessedOn;
    @Column(nullable = false, updatable = false) private LocalDateTime createdAt;
    protected CandidateProfile() {}
    CandidateProfile(Resume resume, CandidateProfileData data, LocalDate assessedOn, String parserVersion, String vocabularyVersion) {
        this.resume = resume; profileData = data; this.assessedOn = assessedOn; schemaVersion = "v1";
        this.parserVersion = parserVersion; this.vocabularyVersion = vocabularyVersion;
    }
    @PrePersist void onCreate() { createdAt = LocalDateTime.now(); }
    Long id() { return id; }
    Resume resume() { return resume; }
    CandidateProfileData data() { return profileData; }
    String schemaVersion() { return schemaVersion; }
    String parserVersion() { return parserVersion; }
    String vocabularyVersion() { return vocabularyVersion; }
    LocalDate assessedOn() { return assessedOn; }
    LocalDateTime createdAt() { return createdAt; }
}

