package com.jobcopilot.discovery;

import com.jobcopilot.discovery.lever.LeverFetchResult;
import com.jobcopilot.discovery.lever.LeverPosting;
import com.jobcopilot.discovery.lever.LeverPostingGateway;
import com.jobcopilot.discovery.lever.LeverRegion;
import com.jobcopilot.job.JobService;
import com.jobcopilot.job.JobStatus;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.aop.support.AopUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LeverJobSourceSynchronizationIntegrationTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @MockitoBean LeverPostingGateway gateway;
    @Autowired LeverJobSourceSynchronizer synchronizer;
    @Autowired JobSourceRepository sources;
    @Autowired ExternalJobListingRepository listings;
    @Autowired JobService jobs;
    @Autowired JdbcTemplate jdbc;
    @Autowired DiscoverySyncTransactions transactions;
    @Autowired JobSourceSyncRunRepository runs;

    @BeforeEach void clean() {
        jdbc.execute("TRUNCATE TABLE job_source_sync_runs, external_job_listings, job_skills, jobs, job_sources RESTART IDENTITY CASCADE");
        reset(gateway);
    }

    @Test void createZeroResultIdempotentCloseAndReopenReuseOneIdentity() {
        JobSource source = sources.saveAndFlush(new JobSource(JobSourceProvider.LEVER, LeverRegion.GLOBAL,
                "example", "Example Company", true));
        respondWith(posting("one", "Required: Java"));

        JobSourceSyncResult first = synchronizer.synchronize(source.id(), JobSourceSyncTrigger.MANUAL);
        ExternalJobListing listing = listings.findByJobSourceAndExternalJobId(source, "one").orElseThrow();
        long listingId = listing.id();
        long jobId = jdbc.queryForObject("SELECT job_id FROM external_job_listings WHERE id = ?",
                Long.class, listingId);
        assertEquals(JobSourceSyncStatus.SUCCEEDED, first.status());
        assertEquals(1, first.counters().created());
        assertEquals(1, first.counters().rankingReady());
        assertEquals(List.of("java"), jobs.getRequirements(jobId).requiredSkills());
        assertEquals(JobStatus.DISCOVERED, jobs.getJob(jobId).status());

        respondWith(posting("one", "A friendly team in a pleasant office."));
        JobSourceSyncResult zero = synchronizer.synchronize(source.id(), JobSourceSyncTrigger.MANUAL);
        String zeroFingerprint = listings.findById(listingId).orElseThrow().extractionFingerprint();
        assertEquals(1, zero.counters().updated());
        assertEquals(1, zero.counters().unready());
        assertTrue(jobs.getRequirements(jobId).requiredSkills().isEmpty());
        assertNotNull(zeroFingerprint);

        respondWith(posting("one", "A friendly team in a pleasant office."));
        JobSourceSyncResult unchanged = synchronizer.synchronize(source.id(), JobSourceSyncTrigger.MANUAL);
        assertEquals(1, unchanged.counters().unchanged());
        assertEquals(1, unchanged.counters().unready());
        assertEquals(zeroFingerprint, listings.findById(listingId).orElseThrow().extractionFingerprint());

        respondWith();
        JobSourceSyncResult closed = synchronizer.synchronize(source.id(), JobSourceSyncTrigger.MANUAL);
        assertEquals(1, closed.counters().closed());
        assertEquals(ListingAvailability.CLOSED, listings.findById(listingId).orElseThrow().availability());

        respondWith(posting("one", "Required: Java"));
        JobSourceSyncResult reopened = synchronizer.synchronize(source.id(), JobSourceSyncTrigger.MANUAL);
        assertEquals(1, reopened.counters().reopened());
        assertEquals(listingId, listings.findByJobSourceAndExternalJobId(source, "one").orElseThrow().id());
        assertEquals(jobId, jdbc.queryForObject("SELECT job_id FROM external_job_listings WHERE id = ?",
                Long.class, listingId));
        assertEquals(JobStatus.DISCOVERED, jobs.getJob(jobId).status());
    }

    @Test void networkCallRunsOutsideTransactionAndRequiresNewMethodsUseSpringProxy() {
        JobSource source = sources.saveAndFlush(new JobSource(JobSourceProvider.LEVER, LeverRegion.GLOBAL,
                "example", "Example Company", true));
        doAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            return new LeverFetchResult.Success(invocation.getArgument(0), invocation.getArgument(1),
                    List.of(), Duration.ZERO);
        }).when(gateway).fetchPage(any(), any());

        assertTrue(AopUtils.isAopProxy(transactions));
        assertEquals(JobSourceSyncStatus.SUCCEEDED,
                synchronizer.synchronize(source.id(), JobSourceSyncTrigger.MANUAL).status());
    }

    @Test void providerFailureNeverClosesOrAdvancesLastSuccess() {
        JobSource source = sources.saveAndFlush(new JobSource(JobSourceProvider.LEVER, LeverRegion.GLOBAL,
                "example", "Example Company", true));
        respondWith(posting("one", "Required: Java"));
        synchronizer.synchronize(source.id(), JobSourceSyncTrigger.MANUAL);
        var previousSuccess = sources.findById(source.id()).orElseThrow().lastSuccessfulSyncAt();
        doAnswer(invocation -> new LeverFetchResult.Failure(
                invocation.getArgument(0), invocation.getArgument(1),
                LeverFetchResult.FailureKind.TIMEOUT, null, Duration.ZERO))
                .when(gateway).fetchPage(any(), any());

        JobSourceSyncResult failed = synchronizer.synchronize(source.id(), JobSourceSyncTrigger.MANUAL);

        assertEquals(JobSourceSyncStatus.FAILED, failed.status());
        assertEquals(ListingAvailability.LIVE,
                listings.findByJobSourceAndExternalJobId(source, "one").orElseThrow().availability());
        assertEquals(previousSuccess, sources.findById(source.id()).orElseThrow().lastSuccessfulSyncAt());
    }

    @Test void duplicateAfterCommittedPagePreservesPresenceAndSuppressesAbsenceInference() {
        JobSource source = sources.saveAndFlush(new JobSource(JobSourceProvider.LEVER, LeverRegion.GLOBAL,
                "example", "Example Company", true));
        respondWith(posting("unseen", "Required: Java"));
        synchronizer.synchronize(source.id(), JobSourceSyncTrigger.MANUAL);
        var previousSuccess = sources.findById(source.id()).orElseThrow().lastSuccessfulSyncAt();
        List<LeverPosting> firstPage = new ArrayList<>();
        for (int index = 0; index < 100; index++) firstPage.add(posting("page-" + index, ""));
        doAnswer(invocation -> {
            var page = (com.jobcopilot.discovery.lever.LeverPageRequest) invocation.getArgument(1);
            List<LeverPosting> values = page.skip() == 0
                    ? firstPage : List.of(posting("page-0", "changed duplicate"));
            return new LeverFetchResult.Success(invocation.getArgument(0), page, values, Duration.ZERO);
        }).when(gateway).fetchPage(any(), any());

        JobSourceSyncResult failed = synchronizer.synchronize(source.id(), JobSourceSyncTrigger.MANUAL);

        assertEquals(JobSourceSyncStatus.FAILED, failed.status());
        assertEquals("DUPLICATE_EXTERNAL_ID_DURING_TRAVERSAL", failed.failureCode());
        assertTrue(listings.findByJobSourceAndExternalJobId(source, "page-0").isPresent());
        assertEquals(ListingAvailability.LIVE,
                listings.findByJobSourceAndExternalJobId(source, "unseen").orElseThrow().availability());
        assertEquals(previousSuccess, sources.findById(source.id()).orElseThrow().lastSuccessfulSyncAt());
    }

    @Test void extractionFailurePreservesRequirementsAndFingerprintButProviderSyncSucceeds() {
        JobSource source = sources.saveAndFlush(new JobSource(JobSourceProvider.LEVER, LeverRegion.GLOBAL,
                "example", "Example Company", true));
        respondWith(posting("one", "Required: Java\nMinimum 3 years of experience"));
        synchronizer.synchronize(source.id(), JobSourceSyncTrigger.MANUAL);
        ExternalJobListing listing = listings.findByJobSourceAndExternalJobId(source, "one").orElseThrow();
        long jobId = jdbc.queryForObject("SELECT job_id FROM external_job_listings WHERE id = ?",
                Long.class, listing.id());
        String successfulFingerprint = listing.extractionFingerprint();

        respondWith(posting("one", "Required: 100 years of experience"));
        JobSourceSyncResult failedExtraction = synchronizer.synchronize(source.id(), JobSourceSyncTrigger.MANUAL);

        assertEquals(JobSourceSyncStatus.SUCCEEDED, failedExtraction.status());
        assertEquals(1, failedExtraction.counters().unready());
        assertEquals(successfulFingerprint, listings.findById(listing.id()).orElseThrow().extractionFingerprint());
        assertEquals(List.of("java"), jobs.getRequirements(jobId).requiredSkills());
        assertEquals(0, jobs.getRequirements(jobId).minYearsExperience().compareTo(new java.math.BigDecimal("3")));
    }

    @Test void blankDescriptionIsAlwaysUnreadyAndDoesNotRunExtraction() {
        JobSource source = sources.saveAndFlush(new JobSource(JobSourceProvider.LEVER, LeverRegion.GLOBAL,
                "example", "Example Company", true));
        respondWith(posting("one", "   "));

        JobSourceSyncResult result = synchronizer.synchronize(source.id(), JobSourceSyncTrigger.MANUAL);

        ExternalJobListing listing = listings.findByJobSourceAndExternalJobId(source, "one").orElseThrow();
        assertEquals(1, result.counters().unready());
        assertEquals(0, result.counters().rankingReady());
        assertNull(listing.extractionFingerprint());
    }

    @Test void startConflictAndRestartRecoveryUseFrozenRunStates() {
        JobSource source = sources.saveAndFlush(new JobSource(JobSourceProvider.LEVER, LeverRegion.GLOBAL,
                "example", "Example Company", true));
        var started = java.time.LocalDateTime.now().minusMinutes(1);
        var first = transactions.start(source.id(), JobSourceSyncTrigger.MANUAL, started);

        assertThrows(JobSourceSyncAlreadyRunningException.class,
                () -> transactions.start(source.id(), JobSourceSyncTrigger.SCHEDULED, started.plusSeconds(1)));
        assertEquals(1, transactions.recover(java.time.LocalDateTime.now()));
        JobSourceSyncRun recovered = runs.findById(first.runId()).orElseThrow();
        assertEquals(JobSourceSyncStatus.ABANDONED, recovered.status());
        assertEquals("APPLICATION_RESTARTED", recovered.failureCode());
        assertDoesNotThrow(() -> transactions.start(source.id(), JobSourceSyncTrigger.SCHEDULED,
                java.time.LocalDateTime.now()));
    }

    @Test void concurrentSameSourceSynchronizationCleanlyRejectsOneCaller() throws Exception {
        JobSource source = sources.saveAndFlush(new JobSource(JobSourceProvider.LEVER, LeverRegion.GLOBAL,
                "example", "Example Company", true));
        CountDownLatch requestStarted = new CountDownLatch(1);
        CountDownLatch releaseRequest = new CountDownLatch(1);
        doAnswer(invocation -> {
            requestStarted.countDown();
            assertTrue(releaseRequest.await(10, TimeUnit.SECONDS));
            return new LeverFetchResult.Success(invocation.getArgument(0), invocation.getArgument(1),
                    List.of(), Duration.ZERO);
        }).when(gateway).fetchPage(any(), any());
        var executor = Executors.newSingleThreadExecutor();
        try {
            var first = executor.submit(() -> synchronizer.synchronize(source.id(), JobSourceSyncTrigger.MANUAL));
            assertTrue(requestStarted.await(10, TimeUnit.SECONDS));

            assertThrows(JobSourceSyncAlreadyRunningException.class,
                    () -> synchronizer.synchronize(source.id(), JobSourceSyncTrigger.SCHEDULED));

            releaseRequest.countDown();
            assertEquals(JobSourceSyncStatus.SUCCEEDED, first.get(10, TimeUnit.SECONDS).status());
        } finally {
            releaseRequest.countDown();
            executor.shutdownNow();
        }
    }

    @Test void finalizationRollbackDoesNotReportClosuresThatDidNotCommit() {
        JobSource source = sources.saveAndFlush(new JobSource(JobSourceProvider.LEVER, LeverRegion.GLOBAL,
                "example", "Example Company", true));
        respondWith(posting("one", "Required: Java"));
        synchronizer.synchronize(source.id(), JobSourceSyncTrigger.MANUAL);
        var forcedFuture = java.time.LocalDateTime.now().plusDays(1);
        jdbc.update("UPDATE job_sources SET created_at = ?, last_successful_sync_at = ? WHERE id = ?",
                java.sql.Timestamp.valueOf(forcedFuture), java.sql.Timestamp.valueOf(forcedFuture), source.id());
        respondWith();

        JobSourceSyncResult failed = synchronizer.synchronize(source.id(), JobSourceSyncTrigger.MANUAL);

        assertEquals(JobSourceSyncStatus.FAILED, failed.status());
        assertEquals("PERSISTENCE_FAILURE", failed.failureCode());
        assertEquals(0, failed.counters().closed());
        assertEquals(0, jdbc.queryForObject(
                "SELECT closed_count FROM job_source_sync_runs WHERE id = ?", Integer.class, failed.runId()));
        assertEquals(ListingAvailability.LIVE,
                listings.findByJobSourceAndExternalJobId(source, "one").orElseThrow().availability());
        assertEquals(DiscoveryTimestamps.toDatabasePrecision(forcedFuture, "forcedFuture"),
                sources.findById(source.id()).orElseThrow().lastSuccessfulSyncAt());
    }

    private void respondWith(LeverPosting... postings) {
        doAnswer(invocation -> new LeverFetchResult.Success(
                invocation.getArgument(0), invocation.getArgument(1), List.of(postings), Duration.ZERO))
                .when(gateway).fetchPage(any(), any());
    }

    private static LeverPosting posting(String id, String description) {
        return LeverPostingMapperTest.posting(id, "Engineer", LeverPostingMapperTest.categories(),
                new LeverPosting.Content(null, description, List.of(), null, null));
    }
}
