package com.jobcopilot.discovery;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DiscoverySchedulingTest {
    @Test void defaultShapeIsOptInAndOperationallyBounded() {
        DiscoverySchedulingProperties properties = properties(false, 2);

        assertFalse(properties.enabled());
        assertEquals(Duration.ofMinutes(1), properties.tickInterval());
        assertEquals(Duration.ofSeconds(30), properties.initialDelay());
        assertEquals(Duration.ofHours(1), properties.syncCadence());
        assertEquals(Duration.ofMinutes(5), properties.leaseTimeout());
        assertEquals(Duration.ofSeconds(30), properties.leaseHeartbeatInterval());
    }

    @Test void invalidSchedulingRelationshipsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new DiscoverySchedulingProperties(
                false, Duration.ofMinutes(2), Duration.ofSeconds(30), Duration.ofMinutes(1), 2,
                Duration.ofMinutes(5), Duration.ofSeconds(30)));
        assertThrows(IllegalArgumentException.class, () -> new DiscoverySchedulingProperties(
                false, Duration.ofMinutes(1), Duration.ofSeconds(30), Duration.ofHours(1), 0,
                Duration.ofMinutes(5), Duration.ofSeconds(30)));
        assertThrows(IllegalArgumentException.class, () -> new DiscoverySchedulingProperties(
                false, Duration.ofMinutes(1), Duration.ofSeconds(30), Duration.ofHours(1), 17,
                Duration.ofMinutes(5), Duration.ofSeconds(30)));
        assertThrows(IllegalArgumentException.class, () -> new DiscoverySchedulingProperties(
                false, Duration.ofMinutes(1), Duration.ofSeconds(30), Duration.ofHours(1), 2,
                Duration.ofSeconds(299), Duration.ofSeconds(30)));
        assertThrows(IllegalArgumentException.class, () -> new DiscoverySchedulingProperties(
                false, Duration.ofMinutes(1), Duration.ofSeconds(30), Duration.ofHours(1), 2,
                Duration.ofMinutes(5), Duration.ofMinutes(5)));
    }

    @Test void workerExecutorUsesFixedConcurrencyAndNoQueue() {
        ThreadPoolTaskExecutor executor = new DiscoverySchedulingConfiguration()
                .discoverySyncExecutor(properties(true, 3));
        executor.initialize();
        try {
            assertEquals(3, executor.getCorePoolSize());
            assertEquals(3, executor.getMaxPoolSize());
            assertInstanceOf(SynchronousQueue.class, executor.getThreadPoolExecutor().getQueue());
        } finally {
            executor.shutdown();
        }
    }

    @Test void capacityRejectionOccursBeforeAnySynchronizationClaim() {
        JobSourceRepository sources = mock(JobSourceRepository.class);
        LeverJobSourceSynchronizer synchronizer = mock(LeverJobSourceSynchronizer.class);
        ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);
        DiscoveryDatabaseTime databaseTime = mock(DiscoveryDatabaseTime.class);
        ScheduledSyncCandidate candidate = candidate(7L);
        when(sources.findScheduledCandidates(anyLong(), anyInt())).thenReturn(List.of(candidate));
        doThrow(new RejectedExecutionException("full")).when(executor).execute(any(Runnable.class));
        ScheduledJobSourceSyncCoordinator coordinator = new ScheduledJobSourceSyncCoordinator(
                sources, synchronizer, executor, properties(true, 2), databaseTime);

        assertEquals(0, coordinator.poll());

        verify(sources).findScheduledCandidates(Duration.ofHours(1).toMillis(), 4);
        verifyNoInteractions(synchronizer);
    }

    @Test void admittedWorkIsOnlyTraversedByTheWorkerRunnableAndSourcesAreIsolated() {
        JobSourceRepository sources = mock(JobSourceRepository.class);
        LeverJobSourceSynchronizer synchronizer = mock(LeverJobSourceSynchronizer.class);
        ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);
        DiscoveryDatabaseTime databaseTime = mock(DiscoveryDatabaseTime.class);
        ScheduledSyncCandidate first = candidate(1L);
        ScheduledSyncCandidate second = candidate(2L);
        when(sources.findScheduledCandidates(anyLong(), anyInt())).thenReturn(List.of(first, second));
        when(databaseTime.now()).thenReturn(LocalDateTime.now());
        when(synchronizer.synchronizeScheduled(1L)).thenThrow(new IllegalStateException("source failure"));
        when(synchronizer.synchronizeScheduled(2L)).thenReturn(java.util.Optional.empty());
        java.util.ArrayList<Runnable> admitted = new java.util.ArrayList<>();
        doAnswer(invocation -> { admitted.add(invocation.getArgument(0)); return null; })
                .when(executor).execute(any(Runnable.class));
        ScheduledJobSourceSyncCoordinator coordinator = new ScheduledJobSourceSyncCoordinator(
                sources, synchronizer, executor, properties(true, 2), databaseTime);

        assertEquals(2, coordinator.poll());
        verifyNoInteractions(synchronizer);
        admitted.forEach(Runnable::run);

        verify(synchronizer).synchronizeScheduled(1L);
        verify(synchronizer).synchronizeScheduled(2L);
    }

    @Test void heartbeatTracksExactRunsAndIsScheduledIndependentlyOfPolling() throws Exception {
        DiscoverySyncTransactions transactions = mock(DiscoverySyncTransactions.class);
        when(transactions.heartbeat(Set.of(11L, 12L))).thenReturn(Set.of(11L, 12L));
        JobSourceSyncHeartbeat heartbeat = new JobSourceSyncHeartbeat(transactions);

        heartbeat.register(11L);
        heartbeat.register(12L);
        heartbeat.renewActiveRuns();
        heartbeat.unregister(11L);

        assertEquals(Set.of(12L), heartbeat.activeRunIds());
        verify(transactions).heartbeat(Set.of(11L, 12L));
        Scheduled annotation = JobSourceSyncHeartbeat.class.getDeclaredMethod("renewActiveRuns")
                .getAnnotation(Scheduled.class);
        assertNotNull(annotation);
        assertEquals("${job-discovery.scheduling.lease-heartbeat-interval:30s}", annotation.fixedDelayString());
    }

    private static DiscoverySchedulingProperties properties(boolean enabled, int concurrency) {
        return new DiscoverySchedulingProperties(enabled, Duration.ofMinutes(1), Duration.ofSeconds(30),
                Duration.ofHours(1), concurrency, Duration.ofMinutes(5), Duration.ofSeconds(30));
    }

    private static ScheduledSyncCandidate candidate(long sourceId) {
        ScheduledSyncCandidate candidate = mock(ScheduledSyncCandidate.class);
        when(candidate.getSourceId()).thenReturn(sourceId);
        when(candidate.getDueAt()).thenReturn(LocalDateTime.now().minusMinutes(1));
        return candidate;
    }
}
