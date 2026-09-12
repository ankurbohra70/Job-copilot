package com.jobcopilot.discovery;

import com.jobcopilot.discovery.lever.LeverRegion;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Objects;

@Entity
@Table(name = "job_sources", uniqueConstraints = @UniqueConstraint(
        name = "uq_job_sources_identity", columnNames = {"provider", "region", "source_key"}))
class JobSource {
    private static final int SOURCE_KEY_MAX_LENGTH = 100;
    private static final int COMPANY_NAME_MAX_LENGTH = 255;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private JobSourceProvider provider;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private LeverRegion region;

    @Column(name = "source_key", nullable = false, length = SOURCE_KEY_MAX_LENGTH)
    private String sourceKey;

    @Column(name = "company_name", nullable = false, length = COMPANY_NAME_MAX_LENGTH)
    private String companyName;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "last_successful_sync_at")
    private LocalDateTime lastSuccessfulSyncAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected JobSource() {
    }

    JobSource(JobSourceProvider provider, LeverRegion region, String sourceKey, String companyName, boolean enabled) {
        this.provider = Objects.requireNonNull(provider, "provider is required");
        this.region = Objects.requireNonNull(region, "region is required");
        this.sourceKey = normalizedSourceKey(sourceKey);
        this.companyName = requiredText(companyName, COMPANY_NAME_MAX_LENGTH, "companyName");
        this.enabled = enabled;
    }

    @PrePersist
    void onCreate() {
        LocalDateTime now = DiscoveryTimestamps.toDatabasePrecision(LocalDateTime.now(), "now");
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = DiscoveryTimestamps.toDatabasePrecision(LocalDateTime.now(), "now");
    }

    void changeEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    void recordSuccessfulSync(LocalDateTime completedAt) {
        LocalDateTime normalizedCompletedAt = DiscoveryTimestamps.toDatabasePrecision(completedAt, "completedAt");
        if (createdAt == null) {
            throw new IllegalStateException("source must be persisted before recording synchronization");
        }
        if (normalizedCompletedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("completedAt must not precede source creation");
        }
        if (lastSuccessfulSyncAt != null && normalizedCompletedAt.isBefore(lastSuccessfulSyncAt)) {
            throw new IllegalArgumentException("completedAt must not move backwards");
        }
        lastSuccessfulSyncAt = normalizedCompletedAt;
    }

    Long id() { return id; }
    JobSourceProvider provider() { return provider; }
    LeverRegion region() { return region; }
    String sourceKey() { return sourceKey; }
    String companyName() { return companyName; }
    boolean enabled() { return enabled; }
    LocalDateTime lastSuccessfulSyncAt() { return lastSuccessfulSyncAt; }
    LocalDateTime createdAt() { return createdAt; }
    LocalDateTime updatedAt() { return updatedAt; }

    private static String normalizedSourceKey(String value) {
        Objects.requireNonNull(value, "sourceKey is required");
        String normalized = trimAsciiSpaces(value);
        if (normalized.isEmpty() || normalized.length() > SOURCE_KEY_MAX_LENGTH
                || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("sourceKey is invalid");
        }
        return normalized.toLowerCase(Locale.ROOT);
    }

    private static String requiredText(String value, int maximumLength, String name) {
        Objects.requireNonNull(value, name + " is required");
        String normalized = value.strip();
        if (normalized.isEmpty() || normalized.length() > maximumLength
                || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return normalized;
    }

    private static String trimAsciiSpaces(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) == ' ') start++;
        while (end > start && value.charAt(end - 1) == ' ') end--;
        return value.substring(start, end);
    }
}
