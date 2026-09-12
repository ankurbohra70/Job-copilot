package com.jobcopilot.discovery;

import com.jobcopilot.discovery.lever.LeverRegion;
import com.jobcopilot.job.Job;
import com.jobcopilot.job.JobService;
import com.jobcopilot.job.JobStatus;
import com.jobcopilot.job.dto.CreateJobRequest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Testcontainers
@Transactional
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DiscoveryPersistenceIntegrationTest {
    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("postgres:17.11-alpine"));

    @DynamicPropertySource
    static void configurePostgreSql(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired private JobSourceRepository sources;
    @Autowired private ExternalJobListingRepository listings;
    @Autowired private JobSourceSyncRunRepository runs;
    @Autowired private JobService jobs;
    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void sourcePersistsStringEnumsNullableSyncAndLifecycleTimestamps() throws Exception {
        JobSource source = sources.saveAndFlush(source(" Example-Site ", true));

        assertNotNull(source.id());
        assertEquals("example-site", source.sourceKey());
        assertNull(source.lastSuccessfulSyncAt());
        assertNotNull(source.createdAt());
        assertEquals(source.createdAt(), source.updatedAt());
        assertEquals(0, source.createdAt().getNano() % 1_000);
        assertEquals("LEVER", jdbc.queryForObject(
                "SELECT provider FROM job_sources WHERE id = ?", String.class, source.id()));
        assertEquals("GLOBAL", jdbc.queryForObject(
                "SELECT region FROM job_sources WHERE id = ?", String.class, source.id()));

        LocalDateTime initialUpdated = source.updatedAt();
        Thread.sleep(5);
        source.changeEnabled(false);
        source.recordSuccessfulSync(LocalDateTime.now().plusSeconds(1));
        sources.flush();

        assertFalse(source.enabled());
        assertNotNull(source.lastSuccessfulSyncAt());
        assertTrue(source.updatedAt().isAfter(initialUpdated));
    }

    @Test
    void sourceIdentityIsCaseInsensitiveBecauseDomainCanonicalizesIt() {
        sources.saveAndFlush(source("Example-Site", true));
        JobSource duplicate = source("  EXAMPLE-SITE  ", false);

        assertThrows(DataIntegrityViolationException.class, () -> sources.saveAndFlush(duplicate));
    }

    @Test
    void databaseRejectsNoncanonicalSourceKeys() {
        assertDatabaseRejects("""
                INSERT INTO job_sources(
                    provider, region, source_key, company_name, enabled, created_at, updated_at)
                VALUES ('LEVER', 'GLOBAL', 'Mixed-Case', 'Company', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """);
        assertDatabaseRejects("""
                INSERT INTO job_sources(
                    provider, region, source_key, company_name, enabled, created_at, updated_at)
                VALUES ('LEVER', 'GLOBAL', ' site ', 'Company', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """);
        assertDatabaseRejects("""
                INSERT INTO job_sources(
                    provider, region, source_key, company_name, enabled, created_at, updated_at)
                VALUES ('LEVER', 'GLOBAL', ?, 'Company', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, "\tsite\t");
    }

    @Test
    void sameCanonicalSourceKeyIsAllowedInDifferentRegions() {
        JobSource global = sources.saveAndFlush(new JobSource(
                JobSourceProvider.LEVER, LeverRegion.GLOBAL, " Example-Site ", "Global Company", true));
        JobSource eu = sources.saveAndFlush(new JobSource(
                JobSourceProvider.LEVER, LeverRegion.EU, " EXAMPLE-SITE ", "EU Company", true));

        assertNotNull(global.id());
        assertNotNull(eu.id());
        assertEquals(global.sourceKey(), eu.sourceKey());
    }

    @Test
    void listingPersistsIdentityEnumsUrlsDigestAndIndependentLiveness() {
        JobSource source = sources.saveAndFlush(source("site", true));
        var jobResponse = jobs.createJob(jobRequest("listing-1"));
        Job job = entityManager.getReference(Job.class, jobResponse.id());
        LocalDateTime observed = LocalDateTime.now();
        LocalDateTime expectedObserved = observed.truncatedTo(ChronoUnit.MICROS);
        ExternalJobListing listing = listings.saveAndFlush(new ExternalJobListing(
                source, job, "posting-1", ListingAvailability.LIVE, "sha256:abc",
                "https://jobs.lever.co/site/posting-1", "https://jobs.lever.co/site/posting-1/apply", observed));

        assertNotNull(listing.id());
        assertEquals(ListingAvailability.LIVE, listing.availability());
        assertEquals("sha256:abc", listing.providerContentDigest());
        assertNull(listing.extractionFingerprint());
        assertEquals(expectedObserved, listing.firstSeenAt());
        assertEquals(JobStatus.DISCOVERED, jobs.getJob(jobResponse.id()).status());
        assertTrue(listings.findByJobSourceAndExternalJobId(source, "posting-1").isPresent());
        assertTrue(listings.findByJobId(jobResponse.id()).isPresent());
        listing.recordSuccessfulExtraction("jc005-v1:sha256:" + "a".repeat(64));
        listings.flush();
        assertEquals("jc005-v1:sha256:" + "a".repeat(64), listing.extractionFingerprint());
        LocalDateTime persistedJobUpdatedAt = jdbc.queryForObject(
                "SELECT updated_at FROM jobs WHERE id = ?", LocalDateTime.class, jobResponse.id());

        listing.recordVerification(ListingAvailability.CLOSED, observed, observed.plusMinutes(5));
        listings.flush();
        assertEquals(ListingAvailability.CLOSED, listing.availability());
        assertEquals(jobResponse.updatedAt(), jobs.getJob(jobResponse.id()).updatedAt());
        assertEquals(persistedJobUpdatedAt, jdbc.queryForObject(
                "SELECT updated_at FROM jobs WHERE id = ?", LocalDateTime.class, jobResponse.id()));
        assertEquals(JobStatus.DISCOVERED, jobs.getJob(jobResponse.id()).status());
    }

    @Test
    void listingAllowsClosedStateAndMaximumLengthUrls() {
        JobSource source = sources.saveAndFlush(source("site", true));
        Job job = entityManager.getReference(Job.class, jobs.createJob(jobRequest("listing-2")).id());
        String prefix = "https://example.test/";
        String maximumUrl = prefix + "a".repeat(2048 - prefix.length());

        ExternalJobListing listing = listings.saveAndFlush(new ExternalJobListing(source, job, "posting-2",
                ListingAvailability.CLOSED, "digest", maximumUrl, maximumUrl, LocalDateTime.now()));

        assertEquals(2048, listing.hostedJobUrl().length());
        assertEquals(ListingAvailability.CLOSED, listing.availability());
    }

    @Test
    void databaseRejectsDuplicateExternalIdentityWithinSource() {
        JobSource source = sources.saveAndFlush(source("site", true));
        Job first = entityManager.getReference(Job.class, jobs.createJob(jobRequest("first")).id());
        Job second = entityManager.getReference(Job.class, jobs.createJob(jobRequest("second")).id());
        LocalDateTime now = LocalDateTime.now();
        listings.saveAndFlush(new ExternalJobListing(source, first, "same", ListingAvailability.LIVE,
                "digest-1", null, null, now));

        assertThrows(DataIntegrityViolationException.class, () -> listings.saveAndFlush(new ExternalJobListing(
                source, second, " same ", ListingAvailability.LIVE, "digest-2", null, null, now)));
    }

    @Test
    void databaseRejectsNoncanonicalExternalJobIds() {
        JobSource source = sources.saveAndFlush(source("site", true));
        Long jobId = jobs.createJob(jobRequest("noncanonical-external-id")).id();

        assertDatabaseRejects("""
                INSERT INTO external_job_listings(
                    job_source_id, job_id, external_job_id, availability, provider_content_digest,
                    first_seen_at, last_seen_at, last_verified_at)
                VALUES (?, ?, ' posting ', 'LIVE', 'digest', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, source.id(), jobId);
        assertDatabaseRejects("""
                INSERT INTO external_job_listings(
                    job_source_id, job_id, external_job_id, availability, provider_content_digest,
                    first_seen_at, last_seen_at, last_verified_at)
                VALUES (?, ?, ?, 'LIVE', 'digest', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, source.id(), jobId, "\tposting\t");
    }

    @Test
    void sameExternalJobIdIsAllowedUnderDifferentSources() {
        JobSource firstSource = sources.saveAndFlush(source("first", true));
        JobSource secondSource = sources.saveAndFlush(source("second", true));
        Job firstJob = entityManager.getReference(Job.class, jobs.createJob(jobRequest("first-job")).id());
        Job secondJob = entityManager.getReference(Job.class, jobs.createJob(jobRequest("second-job")).id());
        LocalDateTime observed = LocalDateTime.now();

        ExternalJobListing first = listings.saveAndFlush(new ExternalJobListing(firstSource, firstJob,
                " same-id ", ListingAvailability.LIVE, "digest-1", null, null, observed));
        ExternalJobListing second = listings.saveAndFlush(new ExternalJobListing(secondSource, secondJob,
                "same-id", ListingAvailability.LIVE, "digest-2", null, null, observed));

        assertNotNull(first.id());
        assertNotNull(second.id());
        assertEquals(first.externalJobId(), second.externalJobId());
    }

    @Test
    void microsecondTimestampPolicySurvivesAllPersistenceRoundTrips() {
        LocalDateTime sourceCompletion = LocalDateTime.of(2030, 1, 1, 10, 0, 0, 999_999_999);
        LocalDateTime listingObserved = LocalDateTime.of(2030, 1, 2, 10, 0, 0, 123_456_999);
        LocalDateTime listingSeen = LocalDateTime.of(2030, 1, 2, 11, 0, 0, 654_321_999);
        LocalDateTime listingVerified = LocalDateTime.of(2030, 1, 2, 12, 0, 0, 999_999_999);
        LocalDateTime runStarted = LocalDateTime.of(2030, 1, 3, 10, 0, 0, 999_999_999);
        LocalDateTime runCompleted = LocalDateTime.of(2030, 1, 3, 11, 0, 0, 123_456_999);

        JobSource source = sources.saveAndFlush(source("timestamp-policy", true));
        source.recordSuccessfulSync(sourceCompletion);
        Job job = entityManager.getReference(Job.class, jobs.createJob(jobRequest("timestamp-policy")).id());
        ExternalJobListing listing = new ExternalJobListing(source, job, "posting", ListingAvailability.LIVE,
                "digest", null, null, listingObserved);
        listing.recordVerification(ListingAvailability.LIVE, listingSeen, listingVerified);
        listings.saveAndFlush(listing);
        JobSourceSyncRun run = new JobSourceSyncRun(source, JobSourceSyncTrigger.MANUAL, runStarted);
        run.succeed(runCompleted, JobSourceSyncCounters.zero());
        runs.saveAndFlush(run);
        sources.flush();

        Long sourceId = source.id();
        Long listingId = listing.id();
        Long runId = run.id();
        entityManager.clear();

        JobSource reloadedSource = sources.findById(sourceId).orElseThrow();
        ExternalJobListing reloadedListing = listings.findById(listingId).orElseThrow();
        JobSourceSyncRun reloadedRun = runs.findById(runId).orElseThrow();
        assertEquals(sourceCompletion.truncatedTo(ChronoUnit.MICROS), reloadedSource.lastSuccessfulSyncAt());
        assertEquals(listingObserved.truncatedTo(ChronoUnit.MICROS), reloadedListing.firstSeenAt());
        assertEquals(listingSeen.truncatedTo(ChronoUnit.MICROS), reloadedListing.lastSeenAt());
        assertEquals(listingVerified.truncatedTo(ChronoUnit.MICROS), reloadedListing.lastVerifiedAt());
        assertEquals(runStarted.truncatedTo(ChronoUnit.MICROS), reloadedRun.startedAt());
        assertEquals(runCompleted.truncatedTo(ChronoUnit.MICROS), reloadedRun.completedAt());
    }

    @Test
    void databaseRejectsAssociatingOneJobWithTwoExternalListings() {
        JobSource firstSource = sources.saveAndFlush(source("first", true));
        JobSource secondSource = sources.saveAndFlush(source("second", true));
        Job job = entityManager.getReference(Job.class, jobs.createJob(jobRequest("one-job")).id());
        LocalDateTime now = LocalDateTime.now();
        listings.saveAndFlush(new ExternalJobListing(firstSource, job, "first-id", ListingAvailability.LIVE,
                "digest-1", null, null, now));

        assertThrows(DataIntegrityViolationException.class, () -> listings.saveAndFlush(new ExternalJobListing(
                secondSource, job, "second-id", ListingAvailability.LIVE, "digest-2", null, null, now)));
    }

    @Test
    void syncRunsPersistTriggersTerminalStatesFailureCodesAndCounters() {
        JobSource source = sources.saveAndFlush(source("site", true));
        LocalDateTime started = LocalDateTime.now();
        JobSourceSyncCounters counts = new JobSourceSyncCounters(10, 2, 1, 5, 1, 1, 8, 2);

        JobSourceSyncRun succeeded = new JobSourceSyncRun(source, JobSourceSyncTrigger.MANUAL, started);
        succeeded.succeed(started.plusSeconds(2), counts);
        runs.saveAndFlush(succeeded);
        JobSourceSyncRun failed = new JobSourceSyncRun(source, JobSourceSyncTrigger.SCHEDULED, started.plusMinutes(1));
        failed.fail(started.plusMinutes(1).plusSeconds(2), "PROVIDER_UNAVAILABLE", JobSourceSyncCounters.zero());
        runs.saveAndFlush(failed);
        JobSourceSyncRun abandoned = new JobSourceSyncRun(source, JobSourceSyncTrigger.SCHEDULED, started.plusMinutes(2));
        abandoned.abandon(started.plusMinutes(2).plusSeconds(2), "STALE_RUN", JobSourceSyncCounters.zero());
        runs.saveAndFlush(abandoned);

        assertEquals(3, runs.count());
        assertEquals(counts, succeeded.counters());
        assertNull(succeeded.failureCode());
        assertEquals("PROVIDER_UNAVAILABLE", failed.failureCode());
        assertEquals(JobSourceSyncStatus.ABANDONED, abandoned.status());
        assertEquals("MANUAL", jdbc.queryForObject(
                "SELECT trigger FROM job_source_sync_runs WHERE id = ?", String.class, succeeded.id()));
        assertEquals("SUCCEEDED", jdbc.queryForObject(
                "SELECT status FROM job_source_sync_runs WHERE id = ?", String.class, succeeded.id()));
    }

    @Test
    void databaseAllowsOneRunningRunAndHistoricalTerminalRunsPerSource() {
        JobSource source = sources.saveAndFlush(source("site", true));
        LocalDateTime now = LocalDateTime.now();
        JobSourceSyncRun historical = new JobSourceSyncRun(source, JobSourceSyncTrigger.MANUAL, now.minusMinutes(2));
        historical.succeed(now.minusMinutes(1), JobSourceSyncCounters.zero());
        runs.saveAndFlush(historical);
        JobSourceSyncRun running = runs.saveAndFlush(new JobSourceSyncRun(source, JobSourceSyncTrigger.SCHEDULED, now));

        assertTrue(runs.findByJobSourceAndStatus(source, JobSourceSyncStatus.RUNNING).isPresent());
        assertThrows(DataIntegrityViolationException.class, () -> runs.saveAndFlush(
                new JobSourceSyncRun(source, JobSourceSyncTrigger.MANUAL, now.plusSeconds(1))));
        assertNotNull(running.id());
    }

    @Test
    void newRunningRunIsAllowedAfterPreviousRunBecomesTerminal() {
        JobSource source = sources.saveAndFlush(source("site", true));
        LocalDateTime now = LocalDateTime.now();
        JobSourceSyncRun first = new JobSourceSyncRun(source, JobSourceSyncTrigger.MANUAL, now);
        runs.saveAndFlush(first);
        first.succeed(now.plusSeconds(1), JobSourceSyncCounters.zero());
        runs.flush();

        JobSourceSyncRun next = runs.saveAndFlush(
                new JobSourceSyncRun(source, JobSourceSyncTrigger.SCHEDULED, now.plusSeconds(2)));

        assertEquals(JobSourceSyncStatus.RUNNING, next.status());
    }

    @Test
    void rejectedManagedCompletionLeavesRunningRunUnchanged() {
        JobSource source = sources.saveAndFlush(source("site", true));
        LocalDateTime started = LocalDateTime.now();
        JobSourceSyncRun run = runs.saveAndFlush(
                new JobSourceSyncRun(source, JobSourceSyncTrigger.MANUAL, started));

        assertThrows(NullPointerException.class, () -> run.succeed(started.plusSeconds(1), null));
        assertEquals(JobSourceSyncStatus.RUNNING, run.status());
        assertNull(run.completedAt());
        assertNull(run.failureCode());
        assertEquals(JobSourceSyncCounters.zero(), run.counters());

        runs.flush();
        entityManager.clear();
        JobSourceSyncRun reloaded = runs.findById(run.id()).orElseThrow();
        assertEquals(JobSourceSyncStatus.RUNNING, reloaded.status());
        assertNull(reloaded.completedAt());
        assertNull(reloaded.failureCode());
        assertEquals(JobSourceSyncCounters.zero(), reloaded.counters());
    }

    @Test
    void rejectedManagedVerificationLeavesListingUnchanged() {
        JobSource source = sources.saveAndFlush(source("site", true));
        Job job = entityManager.getReference(Job.class, jobs.createJob(jobRequest("atomic-verification")).id());
        LocalDateTime observed = LocalDateTime.now();
        LocalDateTime expectedObserved = observed.truncatedTo(ChronoUnit.MICROS);
        ExternalJobListing listing = listings.saveAndFlush(new ExternalJobListing(source, job, "posting",
                ListingAvailability.LIVE, "digest", null, null, observed));

        assertThrows(IllegalArgumentException.class, () -> listing.recordVerification(
                ListingAvailability.CLOSED, observed.minusSeconds(1), observed.plusSeconds(1)));
        assertEquals(ListingAvailability.LIVE, listing.availability());
        assertEquals(expectedObserved, listing.lastSeenAt());
        assertEquals(expectedObserved, listing.lastVerifiedAt());

        listings.flush();
        entityManager.clear();
        ExternalJobListing reloaded = listings.findById(listing.id()).orElseThrow();
        assertEquals(ListingAvailability.LIVE, reloaded.availability());
        assertEquals(expectedObserved, reloaded.lastSeenAt());
        assertEquals(expectedObserved, reloaded.lastVerifiedAt());
    }

    @Test
    void databaseRejectsInvalidPersistedEnumValues() {
        JobSource source = sources.saveAndFlush(source("site", true));
        Long jobId = jobs.createJob(jobRequest("invalid-enums")).id();

        assertDatabaseRejects("""
                INSERT INTO job_sources(provider, region, source_key, company_name, enabled, created_at, updated_at)
                VALUES ('OTHER', 'GLOBAL', 'provider', 'Company', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """);
        assertDatabaseRejects("""
                INSERT INTO job_sources(provider, region, source_key, company_name, enabled, created_at, updated_at)
                VALUES ('LEVER', 'OTHER', 'region', 'Company', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """);
        assertDatabaseRejects("""
                INSERT INTO external_job_listings(
                    job_source_id, job_id, external_job_id, availability, provider_content_digest,
                    first_seen_at, last_seen_at, last_verified_at)
                VALUES (?, ?, 'posting', 'OTHER', 'digest', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, source.id(), jobId);
        assertDatabaseRejects("""
                INSERT INTO job_source_sync_runs(job_source_id, trigger, status, started_at)
                VALUES (?, 'OTHER', 'RUNNING', CURRENT_TIMESTAMP)
                """, source.id());
        assertDatabaseRejects("""
                INSERT INTO job_source_sync_runs(job_source_id, trigger, status, started_at)
                VALUES (?, 'MANUAL', 'OTHER', CURRENT_TIMESTAMP)
                """, source.id());
    }

    @Test
    void databaseRejectsUnsafeFailureCodes() {
        JobSource source = sources.saveAndFlush(source("site", true));

        assertDatabaseRejects("""
                INSERT INTO job_source_sync_runs(
                    job_source_id, trigger, status, failure_code, started_at, completed_at)
                VALUES (?, 'MANUAL', 'FAILED', 'unsafe provider body', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, source.id());
    }

    @Test
    void databaseAcceptsEveryValidTerminalStateCombination() {
        JobSource source = sources.saveAndFlush(source("site", true));

        jdbc.update("""
                INSERT INTO job_source_sync_runs(job_source_id, trigger, status, started_at)
                VALUES (?, 'MANUAL', 'RUNNING', CURRENT_TIMESTAMP)
                """, source.id());
        jdbc.update("""
                INSERT INTO job_source_sync_runs(job_source_id, trigger, status, started_at, completed_at)
                VALUES (?, 'MANUAL', 'SUCCEEDED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, source.id());
        jdbc.update("""
                INSERT INTO job_source_sync_runs(
                    job_source_id, trigger, status, failure_code, started_at, completed_at)
                VALUES (?, 'SCHEDULED', 'FAILED', 'PROVIDER_FAILED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, source.id());
        jdbc.update("""
                INSERT INTO job_source_sync_runs(
                    job_source_id, trigger, status, failure_code, started_at, completed_at)
                VALUES (?, 'SCHEDULED', 'ABANDONED', 'STALE_RUN', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, source.id());

        assertEquals(4, jdbc.queryForObject(
                "SELECT COUNT(*) FROM job_source_sync_runs WHERE job_source_id = ?", Integer.class, source.id()));
    }

    @Test
    void databaseRejectsInvalidTerminalStateCombinations() {
        JobSource source = sources.saveAndFlush(source("site", true));

        assertDatabaseRejects("""
                INSERT INTO job_source_sync_runs(job_source_id, trigger, status, started_at, completed_at)
                VALUES (?, 'MANUAL', 'RUNNING', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, source.id());
        assertDatabaseRejects("""
                INSERT INTO job_source_sync_runs(job_source_id, trigger, status, started_at)
                VALUES (?, 'MANUAL', 'SUCCEEDED', CURRENT_TIMESTAMP)
                """, source.id());
        assertDatabaseRejects("""
                INSERT INTO job_source_sync_runs(
                    job_source_id, trigger, status, failure_code, started_at, completed_at)
                VALUES (?, 'MANUAL', 'SUCCEEDED', 'UNEXPECTED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, source.id());
        assertDatabaseRejects("""
                INSERT INTO job_source_sync_runs(job_source_id, trigger, status, started_at, completed_at)
                VALUES (?, 'MANUAL', 'FAILED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, source.id());
        assertDatabaseRejects("""
                INSERT INTO job_source_sync_runs(
                    job_source_id, trigger, status, failure_code, started_at)
                VALUES (?, 'MANUAL', 'ABANDONED', 'STALE_RUN', CURRENT_TIMESTAMP)
                """, source.id());
    }

    @Test
    void databaseRejectsInvalidTimestampOrdering() {
        JobSource source = sources.saveAndFlush(source("site", true));
        Long jobId = jobs.createJob(jobRequest("invalid-timestamps")).id();

        assertDatabaseRejects("""
                INSERT INTO job_sources(
                    provider, region, source_key, company_name, enabled,
                    last_successful_sync_at, created_at, updated_at)
                VALUES ('LEVER', 'GLOBAL', 'time-source', 'Company', TRUE,
                    TIMESTAMP '2026-09-11 09:00:00', TIMESTAMP '2026-09-11 10:00:00', TIMESTAMP '2026-09-11 10:00:00')
                """);
        assertDatabaseRejects("""
                INSERT INTO external_job_listings(
                    job_source_id, job_id, external_job_id, availability, provider_content_digest,
                    first_seen_at, last_seen_at, last_verified_at)
                VALUES (?, ?, 'posting', 'LIVE', 'digest',
                    TIMESTAMP '2026-09-11 11:00:00', TIMESTAMP '2026-09-11 10:00:00', TIMESTAMP '2026-09-11 12:00:00')
                """, source.id(), jobId);
        assertDatabaseRejects("""
                INSERT INTO external_job_listings(
                    job_source_id, job_id, external_job_id, availability, provider_content_digest,
                    first_seen_at, last_seen_at, last_verified_at)
                VALUES (?, ?, 'posting', 'LIVE', 'digest',
                    TIMESTAMP '2026-09-11 10:00:00', TIMESTAMP '2026-09-11 12:00:00', TIMESTAMP '2026-09-11 11:00:00')
                """, source.id(), jobId);
        assertDatabaseRejects("""
                INSERT INTO job_source_sync_runs(job_source_id, trigger, status, started_at, completed_at)
                VALUES (?, 'MANUAL', 'SUCCEEDED',
                    TIMESTAMP '2026-09-11 11:00:00', TIMESTAMP '2026-09-11 10:00:00')
                """, source.id());
    }

    @Test
    void databaseRejectsNegativeCountersEvenWhenDomainIsBypassed() {
        JobSource source = sources.saveAndFlush(source("site", true));

        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                INSERT INTO job_source_sync_runs(
                    job_source_id, trigger, status, failure_code, started_at, completed_at,
                    discovered_count, created_count, updated_count, unchanged_count,
                    closed_count, reopened_count, ranking_ready_count, unready_count)
                VALUES (?, 'MANUAL', 'SUCCEEDED', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,
                    -1, 0, 0, 0, 0, 0, 0, 0)
                """, source.id()));
    }

    @Test
    void deletingJobCascadesOnlyItsExternalListing() {
        JobSource source = sources.saveAndFlush(source("site", true));
        Long jobId = jobs.createJob(jobRequest("cascade")).id();
        Job job = entityManager.getReference(Job.class, jobId);
        ExternalJobListing listing = listings.saveAndFlush(new ExternalJobListing(source, job, "posting",
                ListingAvailability.LIVE, "digest", null, null, LocalDateTime.now()));
        Long listingId = listing.id();
        entityManager.clear();

        jobs.deleteJob(jobId);
        entityManager.flush();
        entityManager.clear();

        assertFalse(listings.existsById(listingId));
        assertTrue(sources.existsById(source.id()));
    }

    @Test
    void deletingSourceWithDependentListingCannotDeleteItsJob() {
        JobSource source = sources.saveAndFlush(source("site", true));
        Long jobId = jobs.createJob(jobRequest("restrict")).id();
        Job job = entityManager.getReference(Job.class, jobId);
        listings.saveAndFlush(new ExternalJobListing(source, job, "posting", ListingAvailability.LIVE,
                "digest", null, null, LocalDateTime.now()));

        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("DELETE FROM job_sources WHERE id = ?", source.id()));
    }

    @Test
    void deletingSourceWithSyncHistoryIsRestricted() {
        JobSource source = sources.saveAndFlush(source("site", true));
        JobSourceSyncRun run = new JobSourceSyncRun(source, JobSourceSyncTrigger.MANUAL, LocalDateTime.now());
        run.succeed(run.startedAt().plusSeconds(1), JobSourceSyncCounters.zero());
        runs.saveAndFlush(run);

        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("DELETE FROM job_sources WHERE id = ?", source.id()));
    }

    private static JobSource source(String key, boolean enabled) {
        return new JobSource(JobSourceProvider.LEVER, LeverRegion.GLOBAL, key, "Example Company", enabled);
    }

    private static CreateJobRequest jobRequest(String externalId) {
        return new CreateJobRequest("Backend Engineer", "Example Company", "Remote",
                "https://example.test/jobs/" + externalId, "Java role", "MANUAL", externalId);
    }

    private void assertDatabaseRejects(String sql, Object... arguments) {
        jdbc.execute("SAVEPOINT expected_constraint_violation");
        try {
            assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(sql, arguments));
        } finally {
            jdbc.execute("ROLLBACK TO SAVEPOINT expected_constraint_violation");
            jdbc.execute("RELEASE SAVEPOINT expected_constraint_violation");
        }
    }
}
