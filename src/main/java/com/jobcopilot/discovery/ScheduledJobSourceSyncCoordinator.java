package com.jobcopilot.discovery;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        prefix = "job-discovery.scheduling", name = "enabled", havingValue = "true")
class ScheduledJobSourceSyncCoordinator {
    private static final Logger log = LoggerFactory.getLogger(ScheduledJobSourceSyncCoordinator.class);
    private final JobSourceRepository sources;
    private final LeverJobSourceSynchronizer synchronizer;
    private final ThreadPoolTaskExecutor executor;
    private final DiscoverySchedulingProperties properties;
    private final DiscoveryDatabaseTime databaseTime;

    ScheduledJobSourceSyncCoordinator(JobSourceRepository sources, LeverJobSourceSynchronizer synchronizer,
            @Qualifier("discoverySyncExecutor") ThreadPoolTaskExecutor executor,
            DiscoverySchedulingProperties properties, DiscoveryDatabaseTime databaseTime) {
        this.sources = sources;
        this.synchronizer = synchronizer;
        this.executor = executor;
        this.properties = properties;
        this.databaseTime = databaseTime;
    }

    int poll() {
        int candidateLimit = Math.multiplyExact(properties.concurrency(), 2);
        List<ScheduledSyncCandidate> candidates = sources.findScheduledCandidates(
                properties.syncCadence().toMillis(), candidateLimit);
        int admitted = 0;
        for (ScheduledSyncCandidate candidate : candidates) {
            try {
                executor.execute(() -> execute(candidate));
                admitted++;
            } catch (RejectedExecutionException capacity) {
                log.info("Scheduled discovery admission rejected sourceId={} outcome=CAPACITY_REJECTED",
                        candidate.getSourceId());
            }
        }
        log.debug("Scheduled discovery poll candidates={} admitted={}", candidates.size(), admitted);
        return admitted;
    }

    private void execute(ScheduledSyncCandidate candidate) {
        long sourceId = candidate.getSourceId();
        try {
            long lagMillis = schedulingLag(candidate.getDueAt());
            var result = synchronizer.synchronizeScheduled(sourceId);
            if (result.isEmpty()) {
                log.debug("Scheduled discovery skipped sourceId={} outcome=NOT_ELIGIBLE schedulingLagMs={}",
                        sourceId, lagMillis);
            }
        } catch (JobSourceSyncLeaseLostException lost) {
            log.warn("Scheduled discovery lost authority sourceId={} outcome=LEASE_LOST", sourceId);
        } catch (RuntimeException failure) {
            log.error("Scheduled discovery worker failed sourceId={} failureType={}",
                    sourceId, failure.getClass().getSimpleName());
        }
    }

    private long schedulingLag(LocalDateTime dueAt) {
        if (dueAt == null) return 0;
        return Math.max(0, Duration.between(dueAt, databaseTime.now()).toMillis());
    }
}
