package com.jobcopilot.discovery;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "job-discovery.scheduling", name = "enabled", havingValue = "true")
class JobSourceSyncScheduler {
    private final ScheduledJobSourceSyncCoordinator coordinator;

    JobSourceSyncScheduler(ScheduledJobSourceSyncCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    @Scheduled(fixedDelayString = "${job-discovery.scheduling.tick-interval:1m}",
            initialDelayString = "${job-discovery.scheduling.initial-delay:30s}")
    void tick() {
        coordinator.poll();
    }
}
