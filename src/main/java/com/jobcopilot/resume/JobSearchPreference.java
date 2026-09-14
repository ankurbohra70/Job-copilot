package com.jobcopilot.resume;

import com.jobcopilot.application.ResumeStrategy;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;

@Entity
@Table(name = "job_search_preferences")
class JobSearchPreference {
    enum RoleKind { TARGET, EXCLUDED }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @OneToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "candidate_profile_id", nullable = false, unique = true)
    private CandidateProfile candidateProfile;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private ResumeStrategy defaultResumeStrategy;
    @Column(precision = 4, scale = 2) private BigDecimal minimumExperienceToleranceYears;
    @Column(precision = 4, scale = 2) private BigDecimal maximumExperienceToleranceYears;
    private Integer freshnessDays;
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "job_search_preference_roles", joinColumns = @JoinColumn(name = "preference_id"))
    private Set<PreferenceRole> roles = new LinkedHashSet<>();
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "job_search_preference_locations", joinColumns = @JoinColumn(name = "preference_id"))
    @Column(name = "location", length = 255) private Set<String> preferredLocations = new LinkedHashSet<>();
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "job_search_preference_work_arrangements", joinColumns = @JoinColumn(name = "preference_id"))
    @Column(name = "arrangement", length = 16) @Enumerated(EnumType.STRING)
    private Set<WorkArrangement> acceptableWorkArrangements = new LinkedHashSet<>();
    @Column(nullable = false, updatable = false) private LocalDateTime createdAt;
    @Column(nullable = false) private LocalDateTime updatedAt;

    protected JobSearchPreference() {}
    JobSearchPreference(CandidateProfile profile) { candidateProfile = profile; }
    @PrePersist void onCreate() { createdAt = LocalDateTime.now(); updatedAt = createdAt; }
    @PreUpdate void onUpdate() { updatedAt = LocalDateTime.now(); }
    void replace(JobSearchPreferenceData data) {
        defaultResumeStrategy = java.util.Objects.requireNonNull(data.defaultResumeStrategy(), "defaultResumeStrategy is required");
        minimumExperienceToleranceYears = decimal(data.minimumExperienceToleranceYears());
        maximumExperienceToleranceYears = decimal(data.maximumExperienceToleranceYears());
        if (minimumExperienceToleranceYears != null && maximumExperienceToleranceYears != null
                && minimumExperienceToleranceYears.compareTo(maximumExperienceToleranceYears) > 0)
            throw new IllegalArgumentException("minimum experience tolerance must not exceed maximum");
        freshnessDays = data.freshnessDays();
        java.util.List<String> targets = canonicalValues(data.targetRoles());
        java.util.List<String> exclusions = canonicalValues(data.excludedRoles());
        java.util.Set<String> targetKeys = targets.stream().map(JobSearchPreference::key).collect(java.util.stream.Collectors.toSet());
        java.util.Set<String> excludedKeys = exclusions.stream().map(JobSearchPreference::key).collect(java.util.stream.Collectors.toSet());
        if (!java.util.Collections.disjoint(targetKeys, excludedKeys))
            throw new IllegalArgumentException("a role cannot be both targeted and excluded");
        roles.clear();
        targets.forEach(value -> roles.add(new PreferenceRole(RoleKind.TARGET, value)));
        exclusions.forEach(value -> roles.add(new PreferenceRole(RoleKind.EXCLUDED, value)));
        preferredLocations.clear(); preferredLocations.addAll(canonicalValues(data.preferredLocations()));
        acceptableWorkArrangements.clear(); acceptableWorkArrangements.addAll(data.acceptableWorkArrangements());
    }
    JobSearchPreferenceData data() {
        return new JobSearchPreferenceData(defaultResumeStrategy,
                roles.stream().filter(r -> r.kind() == RoleKind.TARGET).map(PreferenceRole::roleTitle).sorted().toList(),
                roles.stream().filter(r -> r.kind() == RoleKind.EXCLUDED).map(PreferenceRole::roleTitle).sorted().toList(),
                preferredLocations.stream().sorted().toList(), Set.copyOf(acceptableWorkArrangements),
                minimumExperienceToleranceYears, maximumExperienceToleranceYears, freshnessDays);
    }
    Long id() { return id; }
    LocalDateTime createdAt() { return createdAt; }
    LocalDateTime updatedAt() { return updatedAt; }
    private static String text(String value) {
        if (value == null || value.strip().isEmpty() || value.length() > 255 || value.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("preference values must be non-blank and at most 255 characters");
        return value.strip();
    }
    private static String key(String value) { return text(value).toLowerCase(java.util.Locale.ROOT); }
    private static java.util.List<String> canonicalValues(java.util.List<String> values) {
        java.util.Map<String, String> unique = new java.util.LinkedHashMap<>();
        for (String value : values) {
            String cleaned = text(value);
            unique.putIfAbsent(cleaned.toLowerCase(java.util.Locale.ROOT), cleaned);
        }
        return java.util.List.copyOf(unique.values());
    }
    private static BigDecimal decimal(BigDecimal value) {
        if (value == null) return null;
        if (value.signum() < 0 || value.compareTo(BigDecimal.valueOf(80)) > 0 || value.scale() > 2)
            throw new IllegalArgumentException("experience tolerance must be between 0 and 80 with at most two decimals");
        return value.setScale(2);
    }
}
