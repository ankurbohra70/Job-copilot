package com.jobcopilot.discovery;

import com.jobcopilot.discovery.lever.LeverFetchResult;
import com.jobcopilot.discovery.lever.LeverPosting;
import com.jobcopilot.discovery.lever.LeverPostingGateway;
import com.jobcopilot.discovery.workday.WorkdayDetailRequest;
import com.jobcopilot.discovery.workday.WorkdayFetchResult;
import com.jobcopilot.discovery.workday.WorkdayJobDetail;
import com.jobcopilot.discovery.workday.WorkdayPageRequest;
import com.jobcopilot.discovery.workday.WorkdayPostingGateway;
import com.jobcopilot.discovery.workday.WorkdaySearchPosting;
import com.jobcopilot.discovery.workday.WorkdaySource;
import java.net.URI;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WorkdayJobSourceSynchronizationIntegrationTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @MockitoBean WorkdayPostingGateway gateway;
    @MockitoBean LeverPostingGateway leverGateway;
    @Autowired JobSourceSyncDispatcher dispatcher;
    @Autowired JobSourceService service;
    @Autowired JobSourceRepository sources;
    @Autowired ExternalJobListingRepository listings;
    @Autowired JobSourceSyncRunRepository runs;
    @Autowired DiscoverySyncTransactions transactions;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach void clean() {
        jdbc.execute("TRUNCATE TABLE job_source_sync_runs, external_job_listings, job_skills, jobs, job_sources RESTART IDENTITY CASCADE");
        reset(gateway, leverGateway);
    }

    @Test void completeRepeatDisappearanceAndReappearanceKeepIdentityAndReleaseLease() {
        JobSource source = workday();
        respond("A", "B", "C");
        JobSourceSyncResult first = sync(source);
        assertEquals(JobSourceSyncStatus.SUCCEEDED, first.status());
        assertEquals(3, first.counters().created());
        assertEquals(3, first.counters().rankingReady());
        assertEquals(3, listings.findByJobSource(source).size());
        Map<String, Long> original = ids(source);
        LocalDateTime success = sources.findById(source.id()).orElseThrow().lastSuccessfulSyncAt();
        assertNotNull(success);
        assertEquals(3L, count("select count(*) from jobs"));
        assertEquals(0L, count("select count(*) from job_source_sync_runs where status = 'RUNNING' or lease_expires_at is not null"));
        assertEquals("WORKDAY", jdbc.queryForObject("select source from jobs where external_job_id = 'B'", String.class));
        assertEquals("Example", jdbc.queryForObject("select company from jobs where external_job_id = 'B'", String.class));

        respond("A", "B", "C");
        JobSourceSyncResult repeat = sync(source);
        assertEquals(3, repeat.counters().unchanged());
        assertEquals(0, repeat.counters().created());
        assertEquals(original, ids(source));
        assertEquals(3L, count("select count(*) from jobs"));
        assertTrue(sources.findById(source.id()).orElseThrow().lastSuccessfulSyncAt().compareTo(success) >= 0);

        respond("A", "C");
        JobSourceSyncResult removed = sync(source);
        assertEquals(1, removed.counters().closed());
        assertEquals(ListingAvailability.CLOSED, listing(source, "B").availability());
        assertEquals(ListingAvailability.LIVE, listing(source, "A").availability());
        assertEquals(ListingAvailability.LIVE, listing(source, "C").availability());

        respond("A", "B", "C");
        JobSourceSyncResult reopened = sync(source);
        assertEquals(1, reopened.counters().reopened());
        assertEquals(ListingAvailability.LIVE, listing(source, "B").availability());
        assertEquals(original, ids(source));
        assertEquals(0L, count("select count(*) from job_source_sync_runs where status = 'RUNNING' or lease_expires_at is not null"));
    }

    @Test void fortyFivePostingsPersistInBoundedPagesAndFailedLaterSearchDoesNotClose() {
        JobSource source = workday();
        List<String> all = java.util.stream.IntStream.range(0, 45).mapToObj(i -> "P" + i).toList();
        doAnswer(invocation -> {
            WorkdayPageRequest page = invocation.getArgument(1);
            List<WorkdaySearchPosting> rows = all.subList(page.offset(),
                    Math.min(page.offset() + page.limit(), all.size())).stream().map(this::row).toList();
            return new WorkdayFetchResult.SearchSuccess(invocation.getArgument(0), page,
                    page.offset() == 0 ? all.size() : 0, rows, Duration.ZERO);
        }).when(gateway).searchPage(any(), any());
        doAnswer(invocation -> detail(invocation.getArgument(0), invocation.getArgument(1),
                ((WorkdayDetailRequest) invocation.getArgument(1)).externalPath().substring(5)))
                .when(gateway).fetchDetail(any(), any());
        JobSourceSyncResult first = sync(source);
        assertEquals(JobSourceSyncStatus.SUCCEEDED, first.status());
        assertEquals(45, first.counters().created());
        assertEquals(45L, count("select count(*) from external_job_listings"));

        reset(gateway);
        doAnswer(invocation -> {
            WorkdayPageRequest page = invocation.getArgument(1);
            if (page.offset() == 20) return new WorkdayFetchResult.Failure(invocation.getArgument(0),
                    WorkdayFetchResult.Operation.SEARCH, page, null,
                    WorkdayFetchResult.FailureKind.CONNECTION_FAILURE, null, Duration.ZERO);
            return new WorkdayFetchResult.SearchSuccess(invocation.getArgument(0), page, all.size(),
                    all.subList(0, 20).stream().map(this::row).toList(), Duration.ZERO);
        }).when(gateway).searchPage(any(), any());
        doAnswer(invocation -> detail(invocation.getArgument(0), invocation.getArgument(1),
                ((WorkdayDetailRequest) invocation.getArgument(1)).externalPath().substring(5)))
                .when(gateway).fetchDetail(any(), any());
        LocalDateTime success = sources.findById(source.id()).orElseThrow().lastSuccessfulSyncAt();
        JobSourceSyncResult failed = sync(source);
        assertEquals("CONNECTION_FAILURE", failed.failureCode());
        assertEquals(45L, count("select count(*) from external_job_listings where availability = 'LIVE'"));
        assertEquals(success, sources.findById(source.id()).orElseThrow().lastSuccessfulSyncAt());
    }

    @ParameterizedTest
    @ValueSource(strings = {"SEARCH_TIMEOUT", "DETAIL_TIMEOUT", "RATE_LIMITED", "MALFORMED_RESPONSE",
            "INCONSISTENT_PAGINATION", "DUPLICATE_ID", "DUPLICATE_PATH", "TRAVERSAL_LIMIT_EXCEEDED"})
    void incompleteTraversalNeverClosesExistingListings(String mode) {
        JobSource source = workday();
        respond("A", "B", "C");
        sync(source);
        Map<String, Long> original = ids(source);
        LocalDateTime success = sources.findById(source.id()).orElseThrow().lastSuccessfulSyncAt();
        configureFailure(mode);

        JobSourceSyncResult failed = sync(source);
        assertEquals(JobSourceSyncStatus.FAILED, failed.status());
        assertEquals(0, failed.counters().closed());
        assertEquals(original, ids(source));
        assertEquals(success, sources.findById(source.id()).orElseThrow().lastSuccessfulSyncAt());
        assertEquals(3L, count("select count(*) from external_job_listings where availability = 'LIVE'"));
        assertEquals(0L, count("select count(*) from job_source_sync_runs where status = 'RUNNING' or lease_expires_at is not null"));
        assertEquals(0L, count("select count(*) from external_job_listings where availability = 'CLOSED'"));
        assertEquals(failed.failureCode(), runs.findById(failed.runId()).orElseThrow().failureCode());
    }

    @Test void providerCallsAreOutsideTransactionsAndSourcesRemainIsolated() {
        JobSource workday = workday();
        JobSource lever = sources.saveAndFlush(new JobSource(JobSourceProvider.LEVER,
                com.jobcopilot.discovery.lever.LeverRegion.GLOBAL, "lever", "Example", true));
        AtomicBoolean inTransaction = new AtomicBoolean();
        respond("A");
        doAnswer(invocation -> {
            inTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
            return detail(invocation.getArgument(0), invocation.getArgument(1), "A");
        }).when(gateway).fetchDetail(any(), any());
        LeverPosting sameLooking = LeverPostingMapperTest.posting("A", "Role A",
                LeverPostingMapperTest.categories(), LeverPostingMapperTest.content());
        when(leverGateway.fetchPage(any(), any())).thenAnswer(invocation ->
                new LeverFetchResult.Success(invocation.getArgument(0), invocation.getArgument(1),
                        List.of(sameLooking), Duration.ZERO));
        sync(workday);
        sync(lever);
        assertFalse(inTransaction.get());
        assertNotEquals(listing(workday, "A").id(), listing(lever, "A").id());
        assertEquals(2L, count("select count(*) from external_job_listings"));

        long leverRunCount = count("select count(*) from job_source_sync_runs where job_source_id = " + lever.id());
        configureFailure("RATE_LIMITED");
        assertEquals(JobSourceSyncStatus.FAILED, sync(workday).status());
        assertEquals(leverRunCount, count("select count(*) from job_source_sync_runs where job_source_id = " + lever.id()));
        assertEquals(ListingAvailability.LIVE, listing(lever, "A").availability());

        respond("A");
        doAnswer(invocation ->
                new LeverFetchResult.Failure(invocation.getArgument(0), invocation.getArgument(1),
                        LeverFetchResult.FailureKind.TIMEOUT, null, Duration.ZERO))
                .when(leverGateway).fetchPage(any(), any());
        long workdayRunCount = count("select count(*) from job_source_sync_runs where job_source_id = " + workday.id());
        assertEquals(JobSourceSyncStatus.FAILED, sync(lever).status());
        assertEquals(workdayRunCount, count("select count(*) from job_source_sync_runs where job_source_id = " + workday.id()));
        assertEquals(ListingAvailability.LIVE, listing(workday, "A").availability());
    }

    @Test void leverAndWorkdayHoldIndependentDatabaseLeases() {
        JobSource workday = workday();
        JobSource lever = sources.saveAndFlush(new JobSource(JobSourceProvider.LEVER,
                com.jobcopilot.discovery.lever.LeverRegion.GLOBAL, "lever", "Example", true));
        var workdayRun = transactions.startManual(workday.id());
        var leverRun = transactions.startManual(lever.id());
        assertNotEquals(workdayRun.runId(), leverRun.runId());
        assertEquals(2L, count("select count(*) from job_source_sync_runs where status = 'RUNNING'"));
        transactions.fail(workday.id(), workdayRun.runId(), "TIMEOUT",
                new DiscoverySyncTransactions.MutableCounters());
        assertEquals(JobSourceSyncStatus.RUNNING, runs.findById(leverRun.runId()).orElseThrow().status());
        transactions.fail(lever.id(), leverRun.runId(), "TIMEOUT",
                new DiscoverySyncTransactions.MutableCounters());
        assertEquals(0L, count("select count(*) from job_source_sync_runs where lease_expires_at is not null"));
    }

    @Test void scheduledWorkdayUsesTheSamePersistenceAndReconciliationPath() {
        JobSource source = workday();
        respond("A", "B");
        JobSourceSyncResult result = dispatcher.synchronizeScheduled(source.id()).orElseThrow();
        assertEquals(JobSourceSyncStatus.SUCCEEDED, result.status());
        assertEquals(JobSourceSyncTrigger.SCHEDULED, runs.findById(result.runId()).orElseThrow().trigger());
        assertEquals(2L, count("select count(*) from external_job_listings"));
        assertEquals(0L, count("select count(*) from job_source_sync_runs where lease_expires_at is not null"));
    }

    @Test void manualServiceDispatchesWorkdayThroughTheSharedPath() {
        JobSource source = workday();
        respond("A");
        var response = service.synchronize(source.id());
        assertEquals(JobSourceSyncStatus.SUCCEEDED, response.status());
        assertEquals(1L, count("select count(*) from external_job_listings"));
    }

    @Test void detailFailureAfterPartialPersistencePreservesUpdatesButNeverReconciles() {
        JobSource source = workday();
        List<String> all = java.util.stream.IntStream.range(0, 50).mapToObj(i -> "J" + i).toList();
        doAnswer(invocation -> {
            WorkdayPageRequest page = invocation.getArgument(1);
            List<WorkdaySearchPosting> rows = all.subList(page.offset(),
                    Math.min(page.offset() + page.limit(), all.size())).stream().map(this::row).toList();
            return new WorkdayFetchResult.SearchSuccess(invocation.getArgument(0), page,
                    page.offset() == 0 ? all.size() : 0, rows, Duration.ZERO);
        }).when(gateway).searchPage(any(), any());
        doAnswer(invocation -> detail(invocation.getArgument(0), invocation.getArgument(1),
                ((WorkdayDetailRequest) invocation.getArgument(1)).externalPath().substring(5)))
                .when(gateway).fetchDetail(any(), any());
        JobSourceSyncResult first = sync(source);
        assertEquals(JobSourceSyncStatus.SUCCEEDED, first.status());
        assertEquals(50, first.counters().created());
        assertEquals(50L, count("select count(*) from external_job_listings where availability = 'LIVE'"));

        reset(gateway);
        List<String> updated = all.stream().map(id -> id + "-v2").toList();
        doAnswer(invocation -> {
            WorkdayPageRequest page = invocation.getArgument(1);
            List<WorkdaySearchPosting> rows = updated.subList(page.offset(),
                    Math.min(page.offset() + page.limit(), updated.size())).stream()
                    .map(id -> new WorkdaySearchPosting("Updated Role " + id, "/job/" + id.replace("-v2", ""),
                            "Hybrid", null, null, List.of())).toList();
            return new WorkdayFetchResult.SearchSuccess(invocation.getArgument(0), page,
                    updated.size(), rows, Duration.ZERO);
        }).when(gateway).searchPage(any(), any());
        doAnswer(invocation -> {
            WorkdayDetailRequest request = invocation.getArgument(1);
            String pathId = request.externalPath().substring(5);
            int index = Integer.parseInt(pathId.substring(1));
            if (index == 25) return new WorkdayFetchResult.Failure(invocation.getArgument(0),
                    WorkdayFetchResult.Operation.DETAIL, null, request,
                    WorkdayFetchResult.FailureKind.TIMEOUT, null, Duration.ZERO);
            URI url = URI.create("https://example.wd5.myworkdayjobs.com/External" + request.externalPath());
            WorkdayJobDetail value = new WorkdayJobDetail(pathId, "Updated Role " + pathId + "-v2",
                    "<p>Required: Python and Java</p>", "Hybrid", null, null, null, null,
                    "REQ-" + pathId, "Posting-" + pathId, "External", url, true, true);
            return new WorkdayFetchResult.DetailSuccess(invocation.getArgument(0), request, value, Duration.ZERO);
        }).when(gateway).fetchDetail(any(), any());

        LocalDateTime successBefore = sources.findById(source.id()).orElseThrow().lastSuccessfulSyncAt();
        JobSourceSyncResult failed = sync(source);
        assertEquals(JobSourceSyncStatus.FAILED, failed.status());
        assertEquals("TIMEOUT", failed.failureCode());
        assertEquals(25, failed.counters().updated());
        assertEquals(0, failed.counters().closed());

        assertEquals(50L, count("select count(*) from external_job_listings where availability = 'LIVE'"));
        assertEquals(0L, count("select count(*) from external_job_listings where availability = 'CLOSED'"));
        assertEquals(successBefore, sources.findById(source.id()).orElseThrow().lastSuccessfulSyncAt());

        for (int i = 0; i < 25; i++) {
            ExternalJobListing listing = listing(source, "J" + i);
            assertEquals(ListingAvailability.LIVE, listing.availability());
            assertNotNull(listing.hostedJobUrl());
        }
        for (int i = 25; i < 50; i++) {
            ExternalJobListing listing = listing(source, "J" + i);
            assertEquals(ListingAvailability.LIVE, listing.availability());
        }

        respond(all.subList(0, 30).toArray(String[]::new));
        JobSourceSyncResult complete = sync(source);
        assertEquals(JobSourceSyncStatus.SUCCEEDED, complete.status());
        assertEquals(20, complete.counters().closed());
        assertEquals(30L, count("select count(*) from external_job_listings where availability = 'LIVE'"));
        assertEquals(20L, count("select count(*) from external_job_listings where availability = 'CLOSED'"));
        assertTrue(sources.findById(source.id()).orElseThrow().lastSuccessfulSyncAt().isAfter(successBefore));
    }

    private JobSource workday() {
        return sources.saveAndFlush(new JobSource(JobSourceProvider.WORKDAY, null, null, "Example", true,
                "example.wd5.myworkdayjobs.com", "example", "External", "en-US"));
    }

    private JobSourceSyncResult sync(JobSource source) {
        return dispatcher.synchronize(source.id(), JobSourceSyncTrigger.MANUAL);
    }

    private void respond(String... ids) {
        List<WorkdaySearchPosting> rows = Arrays.stream(ids).map(this::row).toList();
        doAnswer(invocation -> {
            WorkdayPageRequest page = invocation.getArgument(1);
            int start = Math.min(page.offset(), rows.size());
            int end = Math.min(start + page.limit(), rows.size());
            return new WorkdayFetchResult.SearchSuccess(invocation.getArgument(0), page,
                    page.offset() == 0 ? rows.size() : 0, rows.subList(start, end), Duration.ZERO);
        }).when(gateway).searchPage(any(), any());
        doAnswer(invocation ->
                detail(invocation.getArgument(0), invocation.getArgument(1),
                        ((WorkdayDetailRequest) invocation.getArgument(1)).externalPath().substring(5)))
                .when(gateway).fetchDetail(any(), any());
    }

    private void configureFailure(String mode) {
        reset(gateway);
        if (mode.equals("SEARCH_TIMEOUT") || mode.equals("RATE_LIMITED") || mode.equals("MALFORMED_RESPONSE")) {
            WorkdayFetchResult.FailureKind kind = mode.equals("SEARCH_TIMEOUT")
                    ? WorkdayFetchResult.FailureKind.TIMEOUT
                    : WorkdayFetchResult.FailureKind.valueOf(mode);
            doAnswer(invocation ->
                    new WorkdayFetchResult.Failure(invocation.getArgument(0), WorkdayFetchResult.Operation.SEARCH,
                            invocation.getArgument(1), null, kind, null, Duration.ZERO))
                    .when(gateway).searchPage(any(), any());
            return;
        }
        if (mode.equals("TRAVERSAL_LIMIT_EXCEEDED")) {
            doAnswer(invocation ->
                    new WorkdayFetchResult.SearchSuccess(invocation.getArgument(0), invocation.getArgument(1),
                            10_001, List.of(), Duration.ZERO)).when(gateway).searchPage(any(), any());
            return;
        }
        List<WorkdaySearchPosting> rows = mode.equals("DUPLICATE_PATH")
                ? List.of(row("A"), row("A")) : List.of(row("A"), row("B"));
        int reported = mode.equals("INCONSISTENT_PAGINATION") ? 3 : rows.size();
        doAnswer(invocation ->
                new WorkdayFetchResult.SearchSuccess(invocation.getArgument(0), invocation.getArgument(1),
                        reported, rows, Duration.ZERO)).when(gateway).searchPage(any(), any());
        doAnswer(invocation -> {
            WorkdaySource source = invocation.getArgument(0);
            WorkdayDetailRequest request = invocation.getArgument(1);
            if (mode.equals("DETAIL_TIMEOUT")) return new WorkdayFetchResult.Failure(source,
                    WorkdayFetchResult.Operation.DETAIL, null, request,
                    WorkdayFetchResult.FailureKind.TIMEOUT, null, Duration.ZERO);
            String id = mode.equals("DUPLICATE_ID") ? "A" : request.externalPath().substring(5);
            return detail(source, request, id);
        }).when(gateway).fetchDetail(any(), any());
    }

    private WorkdaySearchPosting row(String id) {
        return new WorkdaySearchPosting("Role " + id, "/job/" + id, "Remote", null, null, List.of());
    }

    private WorkdayFetchResult.DetailSuccess detail(WorkdaySource source, WorkdayDetailRequest request, String id) {
        String pathId = request.externalPath().substring(5);
        URI url = URI.create("https://" + source.endpointHost() + "/" + source.siteKey() + request.externalPath());
        WorkdayJobDetail value = new WorkdayJobDetail(id, "Role " + pathId,
                "<p>Required: Java</p>", "Remote", null, null, null, null,
                "REQ-" + pathId, "Posting-" + pathId, source.siteKey(), url, true, true);
        return new WorkdayFetchResult.DetailSuccess(source, request, value, Duration.ZERO);
    }

    private ExternalJobListing listing(JobSource source, String id) {
        return listings.findByJobSourceAndExternalJobId(source, id).orElseThrow();
    }

    private Map<String, Long> ids(JobSource source) {
        return listings.findByJobSource(source).stream().collect(java.util.stream.Collectors.toMap(
                ExternalJobListing::externalJobId, ExternalJobListing::id));
    }

    private long count(String sql) { return jdbc.queryForObject(sql, Long.class); }
}
