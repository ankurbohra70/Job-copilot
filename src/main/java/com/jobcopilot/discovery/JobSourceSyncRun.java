package com.jobcopilot.discovery;

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
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.Objects;

@Entity
@Table(name = "job_source_sync_runs")
class JobSourceSyncRun {
    private static final int FAILURE_CODE_MAX_LENGTH = 64;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_source_id", nullable = false)
    private JobSource jobSource;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private JobSourceSyncTrigger trigger;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private JobSourceSyncStatus status;

    @Column(name = "failure_code", length = FAILURE_CODE_MAX_LENGTH)
    private String failureCode;

    @Column(name = "started_at", nullable = false, updatable = false)
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "lease_expires_at")
    private LocalDateTime leaseExpiresAt;

    @Column(name = "discovered_count", nullable = false)
    private int discoveredCount;

    @Column(name = "created_count", nullable = false)
    private int createdCount;

    @Column(name = "updated_count", nullable = false)
    private int updatedCount;

    @Column(name = "unchanged_count", nullable = false)
    private int unchangedCount;

    @Column(name = "closed_count", nullable = false)
    private int closedCount;

    @Column(name = "reopened_count", nullable = false)
    private int reopenedCount;

    @Column(name = "ranking_ready_count", nullable = false)
    private int rankingReadyCount;

    @Column(name = "unready_count", nullable = false)
    private int unreadyCount;

    protected JobSourceSyncRun() {
    }

    JobSourceSyncRun(JobSource jobSource, JobSourceSyncTrigger trigger, LocalDateTime startedAt) {
        this(jobSource, trigger, startedAt, startedAt.plusMinutes(5));
    }

    JobSourceSyncRun(JobSource jobSource, JobSourceSyncTrigger trigger, LocalDateTime startedAt,
            LocalDateTime leaseExpiresAt) {
        this.jobSource = Objects.requireNonNull(jobSource, "jobSource is required");
        this.trigger = Objects.requireNonNull(trigger, "trigger is required");
        this.startedAt = DiscoveryTimestamps.toDatabasePrecision(startedAt, "startedAt");
        this.leaseExpiresAt = DiscoveryTimestamps.toDatabasePrecision(leaseExpiresAt, "leaseExpiresAt");
        if (!this.leaseExpiresAt.isAfter(this.startedAt)) {
            throw new IllegalArgumentException("leaseExpiresAt must be after startedAt");
        }
        this.status = JobSourceSyncStatus.RUNNING;
        apply(JobSourceSyncCounters.zero());
    }

    void renewLease(LocalDateTime leaseExpiresAt) {
        if (status != JobSourceSyncStatus.RUNNING) {
            throw new IllegalStateException("only a running synchronization can renew its lease");
        }
        LocalDateTime normalized = DiscoveryTimestamps.toDatabasePrecision(leaseExpiresAt, "leaseExpiresAt");
        if (!normalized.isAfter(this.leaseExpiresAt)) {
            throw new IllegalArgumentException("leaseExpiresAt must move forward");
        }
        this.leaseExpiresAt = normalized;
    }

    void succeed(LocalDateTime completedAt, JobSourceSyncCounters counters) {
        complete(JobSourceSyncStatus.SUCCEEDED, completedAt, null, counters);
    }

    void fail(LocalDateTime completedAt, String failureCode, JobSourceSyncCounters counters) {
        complete(JobSourceSyncStatus.FAILED, completedAt, safeFailureCode(failureCode), counters);
    }

    void abandon(LocalDateTime completedAt, String failureCode, JobSourceSyncCounters counters) {
        complete(JobSourceSyncStatus.ABANDONED, completedAt, safeFailureCode(failureCode), counters);
    }

    private void complete(JobSourceSyncStatus terminalStatus, LocalDateTime completedAt,
            String failureCode, JobSourceSyncCounters counters) {
        if (status != JobSourceSyncStatus.RUNNING) {
            throw new IllegalStateException("only a running synchronization can complete");
        }
        LocalDateTime normalizedCompletedAt = DiscoveryTimestamps.toDatabasePrecision(completedAt, "completedAt");
        if (normalizedCompletedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("completedAt must not precede startedAt");
        }
        Objects.requireNonNull(counters, "counters are required");
        this.status = terminalStatus;
        this.completedAt = normalizedCompletedAt;
        this.failureCode = failureCode;
        this.leaseExpiresAt = null;
        apply(counters);
    }

    private void apply(JobSourceSyncCounters counters) {
        discoveredCount = counters.discovered();
        createdCount = counters.created();
        updatedCount = counters.updated();
        unchangedCount = counters.unchanged();
        closedCount = counters.closed();
        reopenedCount = counters.reopened();
        rankingReadyCount = counters.rankingReady();
        unreadyCount = counters.unready();
    }

    private static String safeFailureCode(String value) {
        Objects.requireNonNull(value, "failureCode is required");
        if (value.length() > FAILURE_CODE_MAX_LENGTH || !value.matches("[A-Z][A-Z0-9_]*")) {
            throw new IllegalArgumentException("failureCode must be a safe internal code");
        }
        return value;
    }

    Long id() { return id; }
    JobSource jobSource() { return jobSource; }
    JobSourceSyncTrigger trigger() { return trigger; }
    JobSourceSyncStatus status() { return status; }
    String failureCode() { return failureCode; }
    LocalDateTime startedAt() { return startedAt; }
    LocalDateTime completedAt() { return completedAt; }
    LocalDateTime leaseExpiresAt() { return leaseExpiresAt; }
    JobSourceSyncCounters counters() {
        return new JobSourceSyncCounters(discoveredCount, createdCount, updatedCount, unchangedCount,
                closedCount, reopenedCount, rankingReadyCount, unreadyCount);
    }
}
