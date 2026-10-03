package com.jobcopilot.discovery;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class JobSourceSyncHeartbeat {
    private static final Logger log = LoggerFactory.getLogger(JobSourceSyncHeartbeat.class);
    private final Set<Long> activeRunIds = ConcurrentHashMap.newKeySet();
    private final DiscoverySyncTransactions transactions;

    JobSourceSyncHeartbeat(DiscoverySyncTransactions transactions) {
        this.transactions = transactions;
    }

    void register(long runId) {
        activeRunIds.add(runId);
    }

    void unregister(long runId) {
        activeRunIds.remove(runId);
    }

    @Scheduled(fixedDelayString = "${job-discovery.scheduling.lease-heartbeat-interval:30s}")
    void renewActiveRuns() {
        Set<Long> snapshot = Set.copyOf(activeRunIds);
        if (snapshot.isEmpty()) return;
        try {
            Set<Long> renewed = transactions.heartbeat(snapshot);
            if (renewed.size() < snapshot.size()) {
                log.debug("Discovery heartbeat skipped locked, expired, or terminal runs registered={} renewed={}",
                        snapshot.size(), renewed.size());
            }
        } catch (RuntimeException failure) {
            log.warn("Discovery heartbeat failed registeredRunCount={} failureType={}",
                    snapshot.size(), failure.getClass().getSimpleName());
        }
    }

    Set<Long> activeRunIds() {
        return Set.copyOf(activeRunIds);
    }
}
