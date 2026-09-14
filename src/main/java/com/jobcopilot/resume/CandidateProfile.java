package com.jobcopilot.resume;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.math.BigDecimal;

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
    @Column(length = 255) private String fullName;
    @Column(length = 320) private String email;
    @Column(length = 64) private String phone;
    @Column(length = 255) private String location;
    @Column(length = 255) private String currentTitle;
    private Integer totalRelevantExperienceMonths;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private CandidateFactState workAuthorization = CandidateFactState.UNKNOWN;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private CandidateFactState sponsorshipRequired = CandidateFactState.UNKNOWN;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private CandidateFactState relocationWilling = CandidateFactState.UNKNOWN;
    private Integer noticePeriodDays;
    @Column(length = 3) private String compensationCurrency;
    @Column(precision = 14, scale = 2) private BigDecimal minimumCompensation;
    @Column(precision = 14, scale = 2) private BigDecimal desiredCompensation;
    protected CandidateProfile() {}
    CandidateProfile(Resume resume, CandidateProfileData data, LocalDate assessedOn, String parserVersion, String vocabularyVersion) {
        this.resume = resume; profileData = data; this.assessedOn = assessedOn; schemaVersion = "v1";
        this.parserVersion = parserVersion; this.vocabularyVersion = vocabularyVersion;
    }
    CandidateProfile(CandidateProfileData data, LocalDate assessedOn) {
        this(null, data, assessedOn, "manual-v1", "v1");
    }
    @PrePersist void onCreate() { createdAt = LocalDateTime.now(); updatedAt = createdAt; }
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
    CandidateProfileFacts facts() {
        return new CandidateProfileFacts(fullName, email, phone, location, currentTitle,
                totalRelevantExperienceMonths, workAuthorization, sponsorshipRequired,
                relocationWilling, noticePeriodDays, compensationCurrency,
                minimumCompensation, desiredCompensation);
    }
    void replaceFacts(CandidateProfileFacts facts) {
        fullName = clean(facts.fullName()); email = clean(facts.email()); phone = clean(facts.phone());
        location = clean(facts.location()); currentTitle = clean(facts.currentTitle());
        totalRelevantExperienceMonths = facts.totalRelevantExperienceMonths();
        workAuthorization = java.util.Objects.requireNonNullElse(facts.workAuthorization(), CandidateFactState.UNKNOWN);
        sponsorshipRequired = java.util.Objects.requireNonNullElse(facts.sponsorshipRequired(), CandidateFactState.UNKNOWN);
        relocationWilling = java.util.Objects.requireNonNullElse(facts.relocationWilling(), CandidateFactState.UNKNOWN);
        noticePeriodDays = facts.noticePeriodDays();
        compensationCurrency = facts.compensationCurrency() == null ? null : facts.compensationCurrency().strip().toUpperCase(java.util.Locale.ROOT);
        minimumCompensation = money(facts.minimumCompensation()); desiredCompensation = money(facts.desiredCompensation());
        if ((minimumCompensation != null || desiredCompensation != null) && compensationCurrency == null)
            throw new IllegalArgumentException("compensationCurrency is required when compensation is provided");
        if (compensationCurrency != null && minimumCompensation == null && desiredCompensation == null)
            throw new IllegalArgumentException("compensation requires a minimum or desired amount");
        if (compensationCurrency != null && !compensationCurrency.matches("[A-Z]{3}"))
            throw new IllegalArgumentException("compensationCurrency must be a three-letter code");
        if (minimumCompensation != null && desiredCompensation != null && desiredCompensation.compareTo(minimumCompensation) < 0)
            throw new IllegalArgumentException("desiredCompensation must not be below minimumCompensation");
    }
    private static BigDecimal money(BigDecimal value) {
        if (value == null) return null;
        if (value.signum() < 0 || value.scale() > 2) throw new IllegalArgumentException("compensation must be non-negative with at most two decimals");
        return value.setScale(2);
    }
    private static String clean(String value) {
        if (value == null) return null;
        String result = value.strip();
        if (result.isEmpty()) return null;
        if (result.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("candidate facts must not contain control characters");
        return result;
    }
}

