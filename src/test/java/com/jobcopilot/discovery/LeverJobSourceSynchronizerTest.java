package com.jobcopilot.discovery;

import com.jobcopilot.discovery.lever.LeverFetchResult;
import com.jobcopilot.discovery.lever.LeverPageRequest;
import com.jobcopilot.discovery.lever.LeverPosting;
import com.jobcopilot.discovery.lever.LeverPostingGateway;
import com.jobcopilot.discovery.lever.LeverRegion;
import com.jobcopilot.discovery.lever.LeverSource;
import com.jobcopilot.job.DiscoveryJobWriter;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LeverJobSourceSynchronizerTest {
    private final DiscoverySyncTransactions transactions = mock(DiscoverySyncTransactions.class);
    private final DiscoveryJobWriter jobs = mock(DiscoveryJobWriter.class);
    private final JobSourceSyncHeartbeat heartbeat = mock(JobSourceSyncHeartbeat.class);
    private final JobSource source = new JobSource(JobSourceProvider.LEVER, LeverRegion.GLOBAL,
            "example", "Example", true);
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-12T10:00:00Z"), ZoneOffset.UTC);

    @BeforeEach void setUp() {
        when(jobs.extractorVersion()).thenReturn("jc005-v1");
        var start = new DiscoverySyncTransactions.Start(source, 10L);
        when(transactions.startManual(1L)).thenReturn(start);
        when(transactions.startScheduled(1L)).thenReturn(
                new DiscoverySyncTransactions.ScheduledStartDecision(start, null, false));
        when(transactions.fail(eq(1L), eq(10L), anyString(), any())).thenAnswer(invocation ->
                result(JobSourceSyncStatus.FAILED, invocation.getArgument(2)));
        when(transactions.succeed(eq(1L), eq(10L), anySet(), any(), any()))
                .thenReturn(result(JobSourceSyncStatus.SUCCEEDED, null));
    }

    @Test void shortPagePersistsThenFinalizes() {
        LeverPostingGateway gateway = (leverSource, page) -> success(leverSource, page, List.of(posting("one")));
        when(transactions.persistPage(eq(1L), eq(10L), anyList(), any())).thenReturn(
                new DiscoverySyncTransactions.PageResult(1, 0, 0, 0, List.of(
                        new DiscoverySyncTransactions.Observation(20L, "fingerprint", false, true))));

        JobSourceSyncResult result = synchronizer(gateway).synchronize(1L, JobSourceSyncTrigger.MANUAL);

        assertEquals(JobSourceSyncStatus.SUCCEEDED, result.status());
        verify(transactions).persistPage(eq(1L), eq(10L), argThat(values -> values.size() == 1), any());
        verify(transactions).succeed(eq(1L), eq(10L), argThat(ids -> ids.equals(java.util.Set.of("one"))),
                any(), any());
        verify(transactions, never()).fail(anyLong(), anyLong(), anyString(), any());
        verify(heartbeat).register(10L);
        verify(heartbeat).unregister(10L);
    }

    @Test void scheduledTraversalUsesTheSameHeartbeatAndTraversalAuthority() {
        LeverPostingGateway gateway = (leverSource, page) -> success(leverSource, page, List.of());
        when(transactions.persistPage(eq(1L), eq(10L), anyList(), any())).thenReturn(
                new DiscoverySyncTransactions.PageResult(0, 0, 0, 0, List.of()));

        assertTrue(synchronizer(gateway).synchronizeScheduled(1L).isPresent());

        verify(transactions).startScheduled(1L);
        verify(transactions).persistPage(eq(1L), eq(10L), anyList(), any());
        verify(heartbeat).register(10L);
        verify(heartbeat).unregister(10L);
    }

    @Test void runningRunConstraintRaceTranslatesToCleanAlreadyRunningFailure() {
        ConstraintViolationException constraint = mock(ConstraintViolationException.class);
        when(constraint.getConstraintName()).thenReturn("ux_job_source_sync_runs_one_running_per_source");
        when(transactions.startManual(1L))
                .thenThrow(new DataIntegrityViolationException("concurrent running-run constraint", constraint));
        LeverPostingGateway gateway = mock(LeverPostingGateway.class);

        assertThrows(JobSourceSyncAlreadyRunningException.class,
                () -> synchronizer(gateway).synchronize(1L, JobSourceSyncTrigger.MANUAL));

        verifyNoInteractions(gateway);
    }

    @Test void unrelatedDatabaseStartFailureIsNotMisreportedAsConcurrency() {
        DataIntegrityViolationException corruption = new DataIntegrityViolationException("unrelated constraint");
        when(transactions.startManual(1L)).thenThrow(corruption);

        assertSame(corruption, assertThrows(DataIntegrityViolationException.class,
                () -> synchronizer(mock(LeverPostingGateway.class))
                        .synchronize(1L, JobSourceSyncTrigger.MANUAL)));
    }

    @Test void anyCrossPageDuplicateFailsBeforeSecondPagePersistence() {
        List<LeverPosting> first = new ArrayList<>();
        for (int i = 0; i < 100; i++) first.add(posting("id-" + i));
        LeverPostingGateway gateway = (leverSource, page) -> page.skip() == 0
                ? success(leverSource, page, first)
                : success(leverSource, page, List.of(posting("id-0")));
        when(transactions.persistPage(eq(1L), eq(10L), anyList(), any())).thenReturn(
                new DiscoverySyncTransactions.PageResult(100, 0, 0, 0, List.of()));

        JobSourceSyncResult result = synchronizer(gateway).synchronize(1L, JobSourceSyncTrigger.MANUAL);

        assertEquals(JobSourceSyncStatus.FAILED, result.status());
        assertEquals("DUPLICATE_EXTERNAL_ID_DURING_TRAVERSAL", result.failureCode());
        verify(transactions, times(1)).persistPage(eq(1L), eq(10L), anyList(), any());
        verify(transactions, never()).succeed(anyLong(), anyLong(), anySet(), any(), any());
    }

    @Test void changedCrossPageDuplicateIsAlsoFatal() {
        List<LeverPosting> first = new ArrayList<>();
        for (int i = 0; i < 100; i++) first.add(posting("id-" + i));
        LeverPosting changed = LeverPostingMapperTest.posting("id-0", "Changed title",
                LeverPostingMapperTest.categories(), LeverPostingMapperTest.content());
        LeverPostingGateway gateway = (leverSource, page) -> page.skip() == 0
                ? success(leverSource, page, first) : success(leverSource, page, List.of(changed));
        when(transactions.persistPage(eq(1L), eq(10L), anyList(), any())).thenReturn(
                new DiscoverySyncTransactions.PageResult(100, 0, 0, 0, List.of()));

        JobSourceSyncResult result = synchronizer(gateway).synchronize(1L, JobSourceSyncTrigger.MANUAL);

        assertEquals("DUPLICATE_EXTERNAL_ID_DURING_TRAVERSAL", result.failureCode());
        verify(transactions, times(1)).persistPage(eq(1L), eq(10L), anyList(), any());
        verify(transactions, never()).succeed(anyLong(), anyLong(), anySet(), any(), any());
    }

    @Test void networkFailureAfterCommittedPageNeverReconciles() {
        List<LeverPosting> first = new ArrayList<>();
        for (int i = 0; i < 100; i++) first.add(posting("id-" + i));
        LeverPostingGateway gateway = (leverSource, page) -> page.skip() == 0
                ? success(leverSource, page, first)
                : new LeverFetchResult.Failure(leverSource, page, LeverFetchResult.FailureKind.TIMEOUT,
                        null, Duration.ZERO);
        when(transactions.persistPage(eq(1L), eq(10L), anyList(), any())).thenReturn(
                new DiscoverySyncTransactions.PageResult(100, 0, 0, 0, List.of()));

        JobSourceSyncResult result = synchronizer(gateway).synchronize(1L, JobSourceSyncTrigger.MANUAL);

        assertEquals("TIMEOUT", result.failureCode());
        verify(transactions, times(1)).persistPage(eq(1L), eq(10L), anyList(), any());
        verify(transactions, never()).succeed(anyLong(), anyLong(), anySet(), any(), any());
    }

    @Test void mappingFailureAfterCommittedPageNeverReconciles() {
        List<LeverPosting> first = new ArrayList<>();
        for (int i = 0; i < 100; i++) first.add(posting("id-" + i));
        LeverPosting invalid = LeverPostingMapperTest.posting("broken", "   ",
                LeverPostingMapperTest.categories(), LeverPostingMapperTest.content());
        LeverPostingGateway gateway = (leverSource, page) -> page.skip() == 0
                ? success(leverSource, page, first) : success(leverSource, page, List.of(invalid));
        when(transactions.persistPage(eq(1L), eq(10L), anyList(), any())).thenReturn(
                new DiscoverySyncTransactions.PageResult(100, 0, 0, 0, List.of()));

        JobSourceSyncResult result = synchronizer(gateway).synchronize(1L, JobSourceSyncTrigger.MANUAL);

        assertEquals("POSTING_MAPPING_FAILED", result.failureCode());
        verify(transactions, times(1)).persistPage(eq(1L), eq(10L), anyList(), any());
        verify(transactions, never()).succeed(anyLong(), anyLong(), anySet(), any(), any());
    }

    @Test void exactFullPageThenEmptyPageCompletesAuthoritatively() {
        List<LeverPosting> first = new ArrayList<>();
        for (int i = 0; i < 100; i++) first.add(posting("id-" + i));
        LeverPostingGateway gateway = (leverSource, page) -> page.skip() == 0
                ? success(leverSource, page, first) : success(leverSource, page, List.of());
        when(transactions.persistPage(eq(1L), eq(10L), anyList(), any())).thenAnswer(invocation -> {
            int size = ((List<?>) invocation.getArgument(2)).size();
            return new DiscoverySyncTransactions.PageResult(size, 0, 0, 0, List.of());
        });

        assertEquals(JobSourceSyncStatus.SUCCEEDED,
                synchronizer(gateway).synchronize(1L, JobSourceSyncTrigger.MANUAL).status());
        verify(transactions, times(2)).persistPage(eq(1L), eq(10L), anyList(), any());
        verify(transactions).succeed(eq(1L), eq(10L), argThat(ids -> ids.size() == 100), any(), any());
    }

    @Test void fullHundredthPageFailsAtSafetyBoundWithoutReconciliation() {
        LeverPostingGateway gateway = (leverSource, page) -> {
            List<LeverPosting> values = new ArrayList<>();
            for (int i = 0; i < 100; i++) values.add(posting("id-" + (page.skip() + i)));
            return success(leverSource, page, values);
        };
        when(transactions.persistPage(eq(1L), eq(10L), anyList(), any())).thenReturn(
                new DiscoverySyncTransactions.PageResult(100, 0, 0, 0, List.of()));

        JobSourceSyncResult result = synchronizer(gateway).synchronize(1L, JobSourceSyncTrigger.SCHEDULED);

        assertEquals("TRAVERSAL_LIMIT_EXCEEDED", result.failureCode());
        verify(transactions, times(100)).persistPage(eq(1L), eq(10L), anyList(), any());
        verify(transactions, never()).succeed(anyLong(), anyLong(), anySet(), any(), any());
    }

    @ParameterizedTest
    @EnumSource(LeverFetchResult.FailureKind.class)
    void everyProviderFailureSuppressesPersistenceAndReconciliation(LeverFetchResult.FailureKind kind) {
        LeverPostingGateway gateway = (leverSource, page) -> new LeverFetchResult.Failure(
                leverSource, page, kind, null, Duration.ZERO);

        JobSourceSyncResult result = synchronizer(gateway).synchronize(1L, JobSourceSyncTrigger.SCHEDULED);

        assertEquals(JobSourceSyncStatus.FAILED, result.status());
        assertEquals(kind.name(), result.failureCode());
        verify(transactions, never()).persistPage(anyLong(), anyLong(), anyList(), any());
        verify(transactions, never()).succeed(anyLong(), anyLong(), anySet(), any(), any());
    }

    private LeverJobSourceSynchronizer synchronizer(LeverPostingGateway gateway) {
        return new LeverJobSourceSynchronizer(gateway, new LeverPostingMapper(new LeverDescriptionAssembler(),
                new ProviderContentDigest(), new ExtractionFingerprint()), transactions, jobs, heartbeat, clock);
    }

    private JobSourceSyncResult result(JobSourceSyncStatus status, String failure) {
        return new JobSourceSyncResult(10L, status, failure,
                new JobSourceSyncResult.Counters(0, 0, 0, 0, 0, 0, 0, 0));
    }

    private static LeverFetchResult.Success success(LeverSource source, LeverPageRequest page,
            List<LeverPosting> postings) {
        return new LeverFetchResult.Success(source, page, postings, Duration.ZERO);
    }

    private static LeverPosting posting(String id) {
        return LeverPostingMapperTest.posting(id, "Engineer", LeverPostingMapperTest.categories(),
                LeverPostingMapperTest.content());
    }
}
