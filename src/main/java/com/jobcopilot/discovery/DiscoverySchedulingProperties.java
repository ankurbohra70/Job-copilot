package com.jobcopilot.discovery;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("job-discovery.scheduling")
public record DiscoverySchedulingProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("1m") Duration tickInterval,
        @DefaultValue("30s") Duration initialDelay,
        @DefaultValue("1h") Duration syncCadence,
        @DefaultValue("2") int concurrency,
        @DefaultValue("5m") Duration leaseTimeout,
        @DefaultValue("30s") Duration leaseHeartbeatInterval) {

    public DiscoverySchedulingProperties {
        positive(tickInterval, "job-discovery.scheduling.tick-interval");
        positive(initialDelay, "job-discovery.scheduling.initial-delay");
        positive(syncCadence, "job-discovery.scheduling.sync-cadence");
        positive(leaseTimeout, "job-discovery.scheduling.lease-timeout");
        positive(leaseHeartbeatInterval, "job-discovery.scheduling.lease-heartbeat-interval");
        if (concurrency < 1 || concurrency > 16) {
            throw new IllegalArgumentException("job-discovery.scheduling.concurrency must be between 1 and 16");
        }
        if (syncCadence.compareTo(tickInterval) < 0) {
            throw new IllegalArgumentException("job-discovery.scheduling.sync-cadence must not be shorter than tick-interval");
        }
        if (leaseHeartbeatInterval.compareTo(leaseTimeout) >= 0) {
            throw new IllegalArgumentException("job-discovery.scheduling.lease-heartbeat-interval must be shorter than lease-timeout");
        }
        Duration minimumLease;
        try {
            minimumLease = leaseHeartbeatInterval.multipliedBy(10);
        } catch (ArithmeticException tooLarge) {
            throw new IllegalArgumentException("job-discovery.scheduling.lease-heartbeat-interval is too large", tooLarge);
        }
        if (leaseTimeout.compareTo(minimumLease) < 0) {
            throw new IllegalArgumentException("job-discovery.scheduling.lease-timeout must allow at least ten heartbeats");
        }
    }

    private static void positive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        try {
            if (value.toMillis() < 1) throw new IllegalArgumentException(name + " must be at least 1ms");
        } catch (ArithmeticException tooLarge) {
            throw new IllegalArgumentException(name + " is too large", tooLarge);
        }
    }
}
