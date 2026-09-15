package com.jobcopilot.resume;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity @Table(name = "candidate_profiles")
class CandidateProfile {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @OneToOne(fetch = FetchType.LAZY) @JoinColumn(name = "resume_id", unique = true)
    private Resume resume;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb") private CandidateProfileData profileData;
    @Column(nullable = false, length = 32) private String schemaVersion;
    @Column(nullable = false, length = 64) private String parserVersion;
    @Column(nullable = false, length = 32) private String vocabularyVersion;
    @Column(nullable = false) private LocalDate assessedOn;
    @Column(nullable = false, updatable = false) private LocalDateTime createdAt;
    @Column(nullable = false) private LocalDateTime updatedAt;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16)
    private CandidateProfileStatus status;
    @Version @Column(nullable = false) private long revision = 1;
    private LocalDateTime confirmedAt;
    @Column(length = 255) private String fullName;
    @Column(length = 320) private String email;
    @Column(length = 64) private String phone;
    @Column(length = 255) private String location;
    @Column(length = 255) private String currentTitle;
    private Integer totalRelevantExperienceMonths;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private CandidateFactState workAuthorization = CandidateFactState.UNKNOWN;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private CandidateFactState sponsorshipRequired = CandidateFactState.UNKNOWN;
    private Integer noticePeriodDays;
    protected CandidateProfile() {}
    CandidateProfile(Resume resume, CandidateProfileData data, LocalDate assessedOn, String parserVersion, String vocabularyVersion) {
        this(resume, data, assessedOn, parserVersion, vocabularyVersion,
                resume == null ? CandidateProfileStatus.CONFIRMED : CandidateProfileStatus.DRAFT);
    }
    CandidateProfile(Resume resume, CandidateProfileData data, LocalDate assessedOn, String parserVersion,
            String vocabularyVersion, CandidateProfileStatus status) {
        this.resume = resume; profileData = data; this.assessedOn = assessedOn; schemaVersion = "v1";
        this.parserVersion = parserVersion; this.vocabularyVersion = vocabularyVersion;
        this.status = status;
    }
    CandidateProfile(CandidateProfileData data, LocalDate assessedOn) {
        this(null, data, assessedOn, "manual-v1", "v1");
    }
    @PrePersist void onCreate() {
        createdAt = LocalDateTime.now(); updatedAt = createdAt;
        if (status == CandidateProfileStatus.CONFIRMED) confirmedAt = createdAt;
    }
    @PreUpdate void onUpdate() { updatedAt = LocalDateTime.now(); }
    Long id() { return id; }
    Resume resume() { return resume; }
    CandidateProfileData data() { return profileData; }
    String schemaVersion() { return schemaVersion; }
    String parserVersion() { return parserVersion; }
    String vocabularyVersion() { return vocabularyVersion; }
    LocalDate assessedOn() { return assessedOn; }
    LocalDateTime createdAt() { return createdAt; }
    LocalDateTime updatedAt() { return updatedAt; }
    CandidateProfileStatus status() { return status; }
    long revision() { return revision; }
    LocalDateTime confirmedAt() { return confirmedAt; }
    CandidateProfileFacts facts() {
        return new CandidateProfileFacts(fullName, email, phone, location, currentTitle,
                totalRelevantExperienceMonths, workAuthorization, sponsorshipRequired, noticePeriodDays);
    }
    void replaceFacts(CandidateProfileFacts facts) {
        java.util.Objects.requireNonNull(facts, "Candidate facts are required");
        fullName = clean(facts.fullName(), 255); email = clean(facts.email(), 320);
        phone = clean(facts.phone(), 64); location = clean(facts.location(), 255);
        currentTitle = clean(facts.currentTitle(), 255);
        if (email != null && !email.matches("^[^\\s@]+@[^\\s@]+$"))
            throw new IllegalArgumentException("email is invalid");
        if (facts.totalRelevantExperienceMonths() != null
                && (facts.totalRelevantExperienceMonths() < 0 || facts.totalRelevantExperienceMonths() > 960))
            throw new IllegalArgumentException("totalRelevantExperienceMonths must be between 0 and 960");
        if (facts.noticePeriodDays() != null
                && (facts.noticePeriodDays() < 0 || facts.noticePeriodDays() > 730))
            throw new IllegalArgumentException("noticePeriodDays must be between 0 and 730");
        totalRelevantExperienceMonths = facts.totalRelevantExperienceMonths();
        workAuthorization = java.util.Objects.requireNonNullElse(facts.workAuthorization(), CandidateFactState.UNKNOWN);
        sponsorshipRequired = java.util.Objects.requireNonNullElse(facts.sponsorshipRequired(), CandidateFactState.UNKNOWN);
        noticePeriodDays = facts.noticePeriodDays();
    }
    boolean confirmOrReplace(CandidateProfileData data, CandidateProfileFacts facts) {
        CandidateProfileData previousData = profileData;
        CandidateProfileFacts previousFacts = facts();
        CandidateProfileStatus previousStatus = status;
        profileData = java.util.Objects.requireNonNull(data);
        replaceFacts(facts);
        boolean changed = previousStatus != CandidateProfileStatus.CONFIRMED
                || !previousData.equals(profileData) || !previousFacts.equals(facts());
        if (!changed) return false;
        status = CandidateProfileStatus.CONFIRMED;
        if (confirmedAt == null) confirmedAt = LocalDateTime.now();
        return true;
    }
    private static String clean(String value, int maximumLength) {
        if (value == null) return null;
        String result = value.strip();
        if (result.isEmpty()) return null;
        if (result.length() > maximumLength || result.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("candidate fact is too long or contains control characters");
        return result;
    }
}

