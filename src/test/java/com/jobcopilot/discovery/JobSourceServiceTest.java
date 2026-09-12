package com.jobcopilot.discovery;

import com.jobcopilot.discovery.JobSourceApiExceptions.DuplicateSource;
import com.jobcopilot.discovery.JobSourceApiExceptions.InvalidSource;
import com.jobcopilot.discovery.JobSourceApiExceptions.PersistenceFailure;
import com.jobcopilot.discovery.dto.CreateJobSourceRequest;
import com.jobcopilot.discovery.lever.LeverRegion;
import com.jobcopilot.job.DiscoveryJobReadService;
import com.jobcopilot.job.JobRequirementExtractor;
import com.jobcopilot.job.JobStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageImpl;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class JobSourceServiceTest {
    private JobSourceRepository sources;
    private JobSourceSyncRunRepository runs;
    private ExternalJobListingRepository listings;
    private LeverJobSourceSynchronizer synchronizer;
    private DiscoveryJobReadService jobReads;
    private ExtractionFingerprint fingerprints;
    private JobRequirementExtractor extractor;
    private JobSourceService service;

    @BeforeEach
    void setUp() {
        sources = mock(JobSourceRepository.class);
        runs = mock(JobSourceSyncRunRepository.class);
        listings = mock(ExternalJobListingRepository.class);
        synchronizer = mock(LeverJobSourceSynchronizer.class);
        jobReads = mock(DiscoveryJobReadService.class);
        fingerprints = new ExtractionFingerprint();
        extractor = new JobRequirementExtractor();
        service = new JobSourceService(sources, runs, listings, synchronizer, fingerprints, extractor, jobReads);
    }

    @Test
    void createUsesDomainCanonicalizationAndNoNetworkCall() {
        when(sources.saveAndFlush(any())).thenAnswer(invocation -> {
            JobSource source = invocation.getArgument(0);
            ReflectionTestUtils.setField(source, "id", 1L);
            source.onCreate();
            return source;
        });

        var response = service.create(new CreateJobSourceRequest(
                JobSourceProvider.LEVER, LeverRegion.GLOBAL, " Example ", "Example", true));

        assertEquals("example", response.sourceKey());
        verifyNoInteractions(synchronizer);
    }

    @Test
    void createRejectsDotSegments() {
        assertThrows(InvalidSource.class, () -> service.create(new CreateJobSourceRequest(
                JobSourceProvider.LEVER, LeverRegion.GLOBAL, ".", "Example", true)));
        assertThrows(InvalidSource.class, () -> service.create(new CreateJobSourceRequest(
                JobSourceProvider.LEVER, LeverRegion.GLOBAL, "..", "Example", true)));
        verifyNoInteractions(sources, synchronizer);
    }

    @Test
    void onlyNamedIdentityConstraintBecomesDuplicate() {
        ConstraintViolationException duplicate = mock(ConstraintViolationException.class);
        when(duplicate.getConstraintName()).thenReturn("uq_job_sources_identity");
        when(sources.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("hidden", duplicate));
        assertThrows(DuplicateSource.class, () -> service.create(request()));

        reset(sources);
        ConstraintViolationException unrelated = mock(ConstraintViolationException.class);
        when(unrelated.getConstraintName()).thenReturn("some_other_constraint");
        when(sources.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("hidden", unrelated));
        assertThrows(PersistenceFailure.class, () -> service.create(request()));
    }

    @Test
    void manualSyncCallsFrozenBoundaryExactlyOnceAndReturnsFailedResult() {
        JobSource source = source(1L, "example", true);
        JobSourceSyncRun run = new JobSourceSyncRun(source, JobSourceSyncTrigger.MANUAL, LocalDateTime.now());
        ReflectionTestUtils.setField(run, "id", 7L);
        run.fail(LocalDateTime.now().plusSeconds(1), "TIMEOUT", JobSourceSyncCounters.zero());
        when(sources.findById(1L)).thenReturn(java.util.Optional.of(source));
        when(synchronizer.synchronize(1L, JobSourceSyncTrigger.MANUAL)).thenReturn(
                new JobSourceSyncResult(7L, JobSourceSyncStatus.FAILED, "TIMEOUT",
                        new JobSourceSyncResult.Counters(0, 0, 0, 0, 0, 0, 0, 0)));
        when(runs.findByIdAndJobSourceId(7L, 1L)).thenReturn(java.util.Optional.of(run));

        var response = service.synchronize(1L);

        assertEquals(JobSourceSyncStatus.FAILED, response.status());
        assertEquals("TIMEOUT", response.failureCode());
        verify(synchronizer, times(1)).synchronize(1L, JobSourceSyncTrigger.MANUAL);
    }

    @Test
    void failureCodesAreAllowlisted() {
        assertEquals("TIMEOUT", JobSourceService.safeFailureCode("TIMEOUT"));
        assertEquals("SYNC_FAILED", JobSourceService.safeFailureCode("PRIVATE_INTERNAL_CODE"));
        assertNull(JobSourceService.safeFailureCode(null));
    }

    @Test
    void listingStatesAndReadinessRespectFingerprintCurrency() {
        String description = "Required: Java";
        String current = fingerprints.calculate(extractor.version(), description);
        var rows = List.of(
                row(1, null, null, BigDecimal.TEN),
                row(2, description, null, BigDecimal.TEN),
                row(3, description, "jc004-v1:sha256:old", BigDecimal.TEN),
                row(4, description, current, null));
        when(sources.findById(1L)).thenReturn(java.util.Optional.of(source(1L, "example", true)));
        when(listings.findPage(eq(1L), isNull(), isNull(), any())).thenReturn(new PageImpl<>(rows));
        when(jobReads.idsWithSkills(anyCollection())).thenReturn(Set.of(104L));

        var content = service.listings(1L, 0, 20, null, null, null).content();

        assertEquals(List.of(ExtractionState.UNAVAILABLE, ExtractionState.PENDING,
                ExtractionState.STALE, ExtractionState.CURRENT),
                content.stream().map(r -> r.extractionState()).toList());
        assertEquals(List.of(false, false, false, true), content.stream().map(r -> r.rankingReady()).toList());
    }

    private static CreateJobSourceRequest request() {
        return new CreateJobSourceRequest(JobSourceProvider.LEVER, LeverRegion.GLOBAL,
                "example", "Example", true);
    }

    private static JobSource source(long id, String key, boolean enabled) {
        JobSource source = new JobSource(JobSourceProvider.LEVER, LeverRegion.GLOBAL, key, "Example", enabled);
        ReflectionTestUtils.setField(source, "id", id);
        return source;
    }

    private static ExternalJobListingReadProjection row(long id, String description,
            String fingerprint, BigDecimal minimum) {
        return new ExternalJobListingReadProjection(id, 1L, "external-" + id, ListingAvailability.LIVE,
                100L + id, JobStatus.DISCOVERED, description, minimum, "https://jobs/" + id,
                "https://apply/" + id, fingerprint, LocalDateTime.now(), LocalDateTime.now(),
                LocalDateTime.now());
    }
}
