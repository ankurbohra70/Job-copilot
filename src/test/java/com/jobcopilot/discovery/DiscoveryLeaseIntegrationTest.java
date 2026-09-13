package com.jobcopilot.discovery;

import com.jobcopilot.JobCopilotApplication;
import com.jobcopilot.discovery.DiscoverySyncTransactions.MutableCounters;
import com.jobcopilot.discovery.lever.LeverRegion;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DiscoveryLeaseIntegrationTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("job-discovery.scheduling.enabled", () -> "false");
    }

    @Autowired DiscoverySyncTransactions transactions;
    @Autowired JobSourceRepository sources;
    @Autowired JobSourceSyncRunRepository runs;
    @Autowired ExternalJobListingRepository listings;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired ApplicationContext context;

    @BeforeEach void clean() {
        jdbc.execute("TRUNCATE TABLE job_source_sync_runs, external_job_listings, job_skills, jobs, job_sources RESTART IDENTITY CASCADE");
    }

    @Test void pollingDisabledStillProvidesHeartbeatInfrastructureWithoutWorkerOrPoller() {
        assertNotNull(context.getBean(JobSourceSyncHeartbeat.class));
        assertFalse(context.containsBean("jobSourceSyncScheduler"));
        assertFalse(context.containsBean("scheduledJobSourceSyncCoordinator"));
        assertFalse(context.containsBean("discoverySyncExecutor"));
    }

    @Test void startingAnotherApplicationInstanceDoesNotAbandonAHealthyRun() {
        JobSource source = source("healthy", true);
        var healthy = transactions.startManual(source.id());

        try (var secondInstance = new SpringApplicationBuilder(JobCopilotApplication.class)
                .web(WebApplicationType.NONE)
                .run(
                        "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "--spring.datasource.username=" + POSTGRES.getUsername(),
                        "--spring.datasource.password=" + POSTGRES.getPassword(),
                        "--spring.jpa.hibernate.ddl-auto=validate",
                        "--job-discovery.scheduling.enabled=false",
                        "--spring.main.banner-mode=off")) {
            assertNotNull(secondInstance.getBean(JobSourceSyncHeartbeat.class));
        }

        JobSourceSyncRun unchanged = runs.findById(healthy.runId()).orElseThrow();
        assertEquals(JobSourceSyncStatus.RUNNING, unchanged.status());
        assertNotNull(unchanged.leaseExpiresAt());
    }

    @Test void competingApplicationLikeClaimantsProduceOneAuthorityForTheSameSource() throws Exception {
        JobSource scheduledSource = source("scheduled-race", true);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var gate = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Callable<DiscoverySyncTransactions.ScheduledStartDecision> claim = () -> {
                gate.await(3, TimeUnit.SECONDS);
                return transactions.startScheduled(scheduledSource.id());
            };
            var first = executor.submit(claim);
            var second = executor.submit(claim);
            gate.countDown();
            List<DiscoverySyncTransactions.ScheduledStartDecision> decisions =
                    List.of(first.get(3, TimeUnit.SECONDS), second.get(3, TimeUnit.SECONDS));
            assertEquals(1, decisions.stream().filter(DiscoverySyncTransactions.ScheduledStartDecision::started).count());
            assertEquals(1, decisions.stream().filter(decision ->
                    decision.skipReason() == DiscoverySyncTransactions.ScheduledSkipReason.ACTIVE_RUN).count());
            assertEquals(1, runs.findAllByStatus(JobSourceSyncStatus.RUNNING).size());
        } finally {
            executor.shutdownNow();
        }

        clean();
        JobSource manualSource = source("manual-race", true);
        var manualExecutor = Executors.newFixedThreadPool(2);
        try {
            var gate = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Callable<Object> claim = () -> {
                gate.await(3, TimeUnit.SECONDS);
                try { return transactions.startManual(manualSource.id()); }
                catch (JobSourceSyncAlreadyRunningException conflict) { return conflict; }
            };
            var first = manualExecutor.submit(claim);
            var second = manualExecutor.submit(claim);
            gate.countDown();
            List<Object> outcomes = List.of(first.get(3, TimeUnit.SECONDS), second.get(3, TimeUnit.SECONDS));
            assertEquals(1, outcomes.stream().filter(DiscoverySyncTransactions.Start.class::isInstance).count());
            assertEquals(1, outcomes.stream().filter(JobSourceSyncAlreadyRunningException.class::isInstance).count());
            assertEquals(1, runs.findAllByStatus(JobSourceSyncStatus.RUNNING).size());
        } finally {
            manualExecutor.shutdownNow();
        }
    }

    @Test void formalEligibilityCoversDisabledNeverRunActiveExpiredRecentAndDueHistory() {
        JobSource disabled = source("disabled", false);
        JobSource never = source("never", true);
        JobSource active = source("active", true);
        JobSource expired = source("expired", true);
        JobSource recent = source("recent", true);
        JobSource due = source("due", true);

        transactions.startManual(active.id());
        var expiredRun = transactions.startManual(expired.id());
        expire(expiredRun.runId());
        var recentRun = transactions.startManual(recent.id());
        transactions.fail(recent.id(), recentRun.runId(), "PROVIDER_FAILED", new MutableCounters());
        var dueRun = transactions.startManual(due.id());
        transactions.fail(due.id(), dueRun.runId(), "PROVIDER_FAILED", new MutableCounters());
        jdbc.update("""
                UPDATE job_source_sync_runs
                SET started_at = clock_timestamp() - interval '2 hours 1 minute',
                    completed_at = clock_timestamp() - interval '2 hours'
                WHERE id = ?
                """, dueRun.runId());

        Set<Long> candidates = sources.findScheduledCandidates(TimeUnit.HOURS.toMillis(1), 20).stream()
                .map(ScheduledSyncCandidate::getSourceId).collect(java.util.stream.Collectors.toSet());

        assertEquals(Set.of(never.id(), expired.id(), due.id()), candidates);
        assertFalse(candidates.contains(disabled.id()));
        assertFalse(candidates.contains(active.id()));
        assertFalse(candidates.contains(recent.id()));
    }

    @Test void claimTimeEligibilityRecoversExpiredImmediatelyAndManualTerminalResetsCadence() {
        JobSource source = source("claim", true);
        var first = transactions.startManual(source.id());
        expire(first.runId());

        var recovered = transactions.startScheduled(source.id());

        assertTrue(recovered.started());
        assertTrue(recovered.recoveredExpiredRun());
        JobSourceSyncRun abandoned = runs.findById(first.runId()).orElseThrow();
        assertEquals(JobSourceSyncStatus.ABANDONED, abandoned.status());
        assertEquals("LEASE_EXPIRED", abandoned.failureCode());
        assertNull(abandoned.leaseExpiresAt());
        assertEquals(JobSourceSyncStatus.RUNNING, runs.findById(recovered.start().runId()).orElseThrow().status());

        transactions.fail(source.id(), recovered.start().runId(), "PROVIDER_FAILED", new MutableCounters());
        var manual = transactions.startManual(source.id());
        transactions.fail(source.id(), manual.runId(), "PROVIDER_FAILED", new MutableCounters());
        var cadence = transactions.startScheduled(source.id());
        assertFalse(cadence.started());
        assertEquals(DiscoverySyncTransactions.ScheduledSkipReason.CADENCE, cadence.skipReason());
    }

    @Test void heartbeatRenewsOnlyValidRunningRowsAndCannotReviveExpiredOrTerminalRows() {
        JobSource validSource = source("valid", true);
        JobSource expiredSource = source("expired-heartbeat", true);
        JobSource terminalSource = source("terminal", true);
        var valid = transactions.startManual(validSource.id());
        var expired = transactions.startManual(expiredSource.id());
        var terminal = transactions.startManual(terminalSource.id());
        LocalDateTime validBefore = lease(valid.runId());
        expire(expired.runId());
        LocalDateTime expiredBefore = lease(expired.runId());
        transactions.fail(terminalSource.id(), terminal.runId(), "PROVIDER_FAILED", new MutableCounters());

        Set<Long> renewed = transactions.heartbeat(Set.of(valid.runId(), expired.runId(), terminal.runId()));

        assertEquals(Set.of(valid.runId()), renewed);
        assertTrue(lease(valid.runId()).isAfter(validBefore));
        assertEquals(expiredBefore, lease(expired.runId()));
        assertNull(lease(terminal.runId()));
        assertThrows(JobSourceSyncLeaseLostException.class,
                () -> transactions.renewBeforeProvider(expiredSource.id(), expired.runId()));
        assertEquals(expiredBefore, lease(expired.runId()));
    }

    @Test void unrelatedLockedRunDoesNotBlockHeartbeatRenewal() throws Exception {
        JobSource firstSource = source("locked", true);
        JobSource secondSource = source("free", true);
        var first = transactions.startManual(firstSource.id());
        var second = transactions.startManual(secondSource.id());
        LocalDateTime firstBefore = lease(first.runId());
        LocalDateTime secondBefore = lease(second.runId());

        try (Connection lock = dataSource.getConnection();
             var statement = lock.prepareStatement("SELECT id FROM job_source_sync_runs WHERE id = ? FOR UPDATE")) {
            lock.setAutoCommit(false);
            statement.setLong(1, first.runId());
            try (var ignored = statement.executeQuery()) { assertTrue(ignored.next()); }
            var executor = Executors.newSingleThreadExecutor();
            try {
                var heartbeat = executor.submit(() -> transactions.heartbeat(Set.of(first.runId(), second.runId())));
                assertEquals(Set.of(second.runId()), heartbeat.get(3, TimeUnit.SECONDS));
                assertEquals(firstBefore, lease(first.runId()));
                assertTrue(lease(second.runId()).isAfter(secondBefore));
            } finally {
                lock.rollback();
                executor.shutdownNow();
            }
        }
    }

    @Test void heartbeatThatWinsBeforeRecoveryKeepsTheRunAuthoritative() throws Exception {
        JobSource source = source("heartbeat-wins", true);
        var run = transactions.startManual(source.id());
        LocalDateTime leaseBefore = lease(run.runId());

        try (Connection sourceLock = dataSource.getConnection();
             var lock = sourceLock.prepareStatement("SELECT id FROM job_sources WHERE id = ? FOR UPDATE")) {
            sourceLock.setAutoCommit(false);
            lock.setLong(1, source.id());
            try (var ignored = lock.executeQuery()) { assertTrue(ignored.next()); }

            var recoveryEntered = new java.util.concurrent.CountDownLatch(1);
            var executor = Executors.newSingleThreadExecutor();
            try {
                var recovery = executor.submit(() -> {
                    recoveryEntered.countDown();
                    return transactions.startScheduled(source.id());
                });
                assertTrue(recoveryEntered.await(3, TimeUnit.SECONDS));

                assertEquals(Set.of(run.runId()), transactions.heartbeat(Set.of(run.runId())));
                assertTrue(lease(run.runId()).isAfter(leaseBefore));
                sourceLock.commit();

                var decision = recovery.get(3, TimeUnit.SECONDS);
                assertFalse(decision.started());
                assertEquals(DiscoverySyncTransactions.ScheduledSkipReason.ACTIVE_RUN, decision.skipReason());
                assertEquals(JobSourceSyncStatus.RUNNING, runs.findById(run.runId()).orElseThrow().status());
            } finally {
                sourceLock.rollback();
                executor.shutdownNow();
            }
        }
    }

    @Test void recoveryThatWinsFirstPreventsTheOldHeartbeatFromRevivingTheRun() {
        JobSource source = source("recovery-wins", true);
        var old = transactions.startManual(source.id());
        expire(old.runId());

        var replacement = transactions.startScheduled(source.id());

        assertTrue(replacement.started());
        assertTrue(replacement.recoveredExpiredRun());
        assertEquals(Set.of(), transactions.heartbeat(Set.of(old.runId())));
        JobSourceSyncRun abandoned = runs.findById(old.runId()).orElseThrow();
        assertEquals(JobSourceSyncStatus.ABANDONED, abandoned.status());
        assertEquals("LEASE_EXPIRED", abandoned.failureCode());
        assertNull(abandoned.leaseExpiresAt());
        assertEquals(JobSourceSyncStatus.RUNNING,
                runs.findById(replacement.start().runId()).orElseThrow().status());
    }

    @Test void validMutationLockMakesRecoveryWaitAndObserveItsRenewedLease() throws Exception {
        JobSource source = source("race", true);
        var run = transactions.startManual(source.id());

        try (Connection mutation = dataSource.getConnection();
             var lock = mutation.prepareStatement("SELECT id FROM job_source_sync_runs WHERE id = ? FOR UPDATE");
             var renew = mutation.prepareStatement("""
                     UPDATE job_source_sync_runs
                     SET lease_expires_at = clock_timestamp() + interval '5 minutes' WHERE id = ?
                     """)) {
            mutation.setAutoCommit(false);
            lock.setLong(1, run.runId());
            try (var ignored = lock.executeQuery()) { assertTrue(ignored.next()); }
            expireWithConnection(mutation, run.runId());

            var executor = Executors.newSingleThreadExecutor();
            try {
                var recovery = executor.submit(() -> transactions.startScheduled(source.id()));
                Thread.sleep(200);
                assertFalse(recovery.isDone(), "recovery must wait for the authoritative run lock");
                renew.setLong(1, run.runId());
                assertEquals(1, renew.executeUpdate());
                mutation.commit();

                var decision = recovery.get(3, TimeUnit.SECONDS);
                assertFalse(decision.started());
                assertEquals(DiscoverySyncTransactions.ScheduledSkipReason.ACTIVE_RUN, decision.skipReason());
                assertEquals(JobSourceSyncStatus.RUNNING, runs.findById(run.runId()).orElseThrow().status());
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test void abandonedRunIsFencedFromEveryCanonicalMutationAndTerminalizationPath() {
        JobSource source = source("fenced", true);
        var old = transactions.startManual(source.id());
        LocalDateTime observed = LocalDateTime.now();
        LeverMappedListing original = mapped("one", "Original title", "digest-one", null);
        var page = transactions.persistPage(source.id(), old.runId(), List.of(original), observed);
        long listingId = page.observations().getFirst().listingId();
        long jobId = jdbc.queryForObject("SELECT job_id FROM external_job_listings WHERE id = ?", Long.class,
                listingId);
        jdbc.update("UPDATE external_job_listings SET availability = 'CLOSED' WHERE id = ?", listingId);
        expire(old.runId());
        var replacement = transactions.startScheduled(source.id());
        assertTrue(replacement.started());

        LeverMappedListing changed = mapped("one", "Stale changed title", "digest-two", "new-fingerprint");
        assertThrows(JobSourceSyncLeaseLostException.class, () -> transactions.persistPage(
                source.id(), old.runId(), List.of(changed), LocalDateTime.now()));
        assertThrows(JobSourceSyncLeaseLostException.class, () -> transactions.extract(
                source.id(), old.runId(), listingId, "new-fingerprint"));
        assertThrows(JobSourceSyncLeaseLostException.class, () -> transactions.succeed(
                source.id(), old.runId(), Set.of(), LocalDateTime.now(), new MutableCounters()));
        assertThrows(JobSourceSyncLeaseLostException.class, () -> transactions.fail(
                source.id(), old.runId(), "PERSISTENCE_FAILURE", new MutableCounters()));

        assertEquals("Original title", jdbc.queryForObject("SELECT title FROM jobs WHERE id = ?", String.class, jobId));
        assertEquals("CLOSED", jdbc.queryForObject(
                "SELECT availability FROM external_job_listings WHERE id = ?", String.class, listingId));
        assertNull(jdbc.queryForObject(
                "SELECT extraction_fingerprint FROM external_job_listings WHERE id = ?", String.class, listingId));
        assertNull(sources.findById(source.id()).orElseThrow().lastSuccessfulSyncAt());
        JobSourceSyncRun abandoned = runs.findById(old.runId()).orElseThrow();
        assertEquals(JobSourceSyncStatus.ABANDONED, abandoned.status());
        assertEquals(JobSourceSyncCounters.zero(), abandoned.counters());
        assertEquals(JobSourceSyncStatus.RUNNING,
                runs.findById(replacement.start().runId()).orElseThrow().status());
    }

    private JobSource source(String key, boolean enabled) {
        return sources.saveAndFlush(new JobSource(JobSourceProvider.LEVER, LeverRegion.GLOBAL,
                key, key + " Company", enabled));
    }

    private void expire(long runId) {
        jdbc.update("UPDATE job_source_sync_runs SET lease_expires_at = clock_timestamp() - interval '1 second' WHERE id = ?",
                runId);
    }

    private static void expireWithConnection(Connection connection, long runId) throws Exception {
        try (var statement = connection.prepareStatement("""
                UPDATE job_source_sync_runs SET lease_expires_at = clock_timestamp() - interval '1 second'
                WHERE id = ?
                """)) {
            statement.setLong(1, runId);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private LocalDateTime lease(long runId) {
        return jdbc.queryForObject("SELECT lease_expires_at FROM job_source_sync_runs WHERE id = ?",
                LocalDateTime.class, runId);
    }

    private static LeverMappedListing mapped(String id, String title, String digest, String fingerprint) {
        return new LeverMappedListing(id, title, "Company", "Remote", "https://jobs.example/" + id,
                "https://jobs.example/" + id + "/apply", "Required: Java", digest, fingerprint);
    }
}
