package com.jobcopilot.discovery;

import com.jobcopilot.job.Job;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;
import java.util.Objects;

@Entity
@Table(name = "external_job_listings", uniqueConstraints = {
        @UniqueConstraint(name = "uq_external_job_listings_source_external_id",
                columnNames = {"job_source_id", "external_job_id"}),
        @UniqueConstraint(name = "uq_external_job_listings_job", columnNames = "job_id")
})
class ExternalJobListing {
    private static final int EXTERNAL_ID_MAX_LENGTH = 255;
    private static final int DIGEST_MAX_LENGTH = 128;
    private static final int URL_MAX_LENGTH = 2048;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_source_id", nullable = false)
    private JobSource jobSource;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_id", nullable = false, unique = true)
    private Job job;

    @Column(name = "external_job_id", nullable = false, length = EXTERNAL_ID_MAX_LENGTH)
    private String externalJobId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ListingAvailability availability;

    @Column(name = "provider_content_digest", nullable = false, length = DIGEST_MAX_LENGTH)
    private String providerContentDigest;

    @Column(name = "hosted_job_url", length = URL_MAX_LENGTH)
    private String hostedJobUrl;

    @Column(name = "apply_url", length = URL_MAX_LENGTH)
    private String applyUrl;

    @Column(name = "extraction_fingerprint", length = DIGEST_MAX_LENGTH)
    private String extractionFingerprint;

    @Column(name = "first_seen_at", nullable = false, updatable = false)
    private LocalDateTime firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    private LocalDateTime lastSeenAt;

    @Column(name = "last_verified_at", nullable = false)
    private LocalDateTime lastVerifiedAt;

    protected ExternalJobListing() {
    }

    ExternalJobListing(JobSource jobSource, Job job, String externalJobId,
            ListingAvailability availability, String providerContentDigest,
            String hostedJobUrl, String applyUrl, LocalDateTime observedAt) {
        this.jobSource = Objects.requireNonNull(jobSource, "jobSource is required");
        this.job = Objects.requireNonNull(job, "job is required");
        this.externalJobId = requiredIdentityText(externalJobId, EXTERNAL_ID_MAX_LENGTH, "externalJobId");
        this.availability = Objects.requireNonNull(availability, "availability is required");
        this.providerContentDigest = requiredText(providerContentDigest, DIGEST_MAX_LENGTH, "providerContentDigest");
        this.hostedJobUrl = optionalText(hostedJobUrl, URL_MAX_LENGTH, "hostedJobUrl");
        this.applyUrl = optionalText(applyUrl, URL_MAX_LENGTH, "applyUrl");
        LocalDateTime normalizedObservedAt = DiscoveryTimestamps.toDatabasePrecision(observedAt, "observedAt");
        this.firstSeenAt = normalizedObservedAt;
        this.lastSeenAt = normalizedObservedAt;
        this.lastVerifiedAt = normalizedObservedAt;
    }

    void recordVerification(ListingAvailability availability, LocalDateTime lastSeenAt,
            LocalDateTime lastVerifiedAt) {
        Objects.requireNonNull(availability, "availability is required");
        LocalDateTime normalizedLastSeenAt = DiscoveryTimestamps.toDatabasePrecision(lastSeenAt, "lastSeenAt");
        LocalDateTime normalizedLastVerifiedAt = DiscoveryTimestamps.toDatabasePrecision(
                lastVerifiedAt, "lastVerifiedAt");
        if (normalizedLastSeenAt.isBefore(firstSeenAt) || normalizedLastSeenAt.isBefore(this.lastSeenAt)
                || normalizedLastVerifiedAt.isBefore(normalizedLastSeenAt)
                || normalizedLastVerifiedAt.isBefore(this.lastVerifiedAt)) {
            throw new IllegalArgumentException("listing timestamps must not move backwards");
        }
        this.availability = availability;
        this.lastSeenAt = normalizedLastSeenAt;
        this.lastVerifiedAt = normalizedLastVerifiedAt;
    }

    void recordLiveContent(String providerContentDigest, String hostedJobUrl, String applyUrl,
            LocalDateTime observedAt) {
        String normalizedDigest = requiredText(providerContentDigest, DIGEST_MAX_LENGTH,
                "providerContentDigest");
        String normalizedHostedUrl = optionalText(hostedJobUrl, URL_MAX_LENGTH, "hostedJobUrl");
        String normalizedApplyUrl = optionalText(applyUrl, URL_MAX_LENGTH, "applyUrl");
        LocalDateTime normalizedObservedAt = DiscoveryTimestamps.toDatabasePrecision(observedAt, "observedAt");
        if (normalizedObservedAt.isBefore(firstSeenAt) || normalizedObservedAt.isBefore(lastSeenAt)
                || normalizedObservedAt.isBefore(lastVerifiedAt)) {
            throw new IllegalArgumentException("listing timestamps must not move backwards");
        }
        this.providerContentDigest = normalizedDigest;
        this.hostedJobUrl = normalizedHostedUrl;
        this.applyUrl = normalizedApplyUrl;
        this.availability = ListingAvailability.LIVE;
        this.lastSeenAt = normalizedObservedAt;
        this.lastVerifiedAt = normalizedObservedAt;
    }

    void recordSuccessfulExtraction(String extractionFingerprint) {
        String normalized = requiredText(extractionFingerprint, DIGEST_MAX_LENGTH, "extractionFingerprint");
        this.extractionFingerprint = normalized;
    }

    Long id() { return id; }
    JobSource jobSource() { return jobSource; }
    Job job() { return job; }
    String externalJobId() { return externalJobId; }
    ListingAvailability availability() { return availability; }
    String providerContentDigest() { return providerContentDigest; }
    String hostedJobUrl() { return hostedJobUrl; }
    String applyUrl() { return applyUrl; }
    String extractionFingerprint() { return extractionFingerprint; }
    LocalDateTime firstSeenAt() { return firstSeenAt; }
    LocalDateTime lastSeenAt() { return lastSeenAt; }
    LocalDateTime lastVerifiedAt() { return lastVerifiedAt; }

    private static String requiredText(String value, int maximumLength, String name) {
        String normalized = optionalText(value, maximumLength, name);
        if (normalized == null) throw new IllegalArgumentException(name + " is required");
        return normalized;
    }

    private static String requiredIdentityText(String value, int maximumLength, String name) {
        Objects.requireNonNull(value, name + " is required");
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) == ' ') start++;
        while (end > start && value.charAt(end - 1) == ' ') end--;
        String normalized = value.substring(start, end);
        if (normalized.isEmpty() || normalized.length() > maximumLength
                || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return normalized;
    }

    private static String optionalText(String value, int maximumLength, String name) {
        if (value == null) return null;
        String normalized = value.strip();
        if (normalized.isEmpty() || normalized.length() > maximumLength
                || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return normalized;
    }
}
