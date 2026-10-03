package com.jobcopilot.discovery;

import com.jobcopilot.discovery.lever.LeverRegion;
import com.jobcopilot.job.Job;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class DiscoveryDomainTest {
    private static final LocalDateTime START = LocalDateTime.of(2026, 9, 11, 10, 0);

    @Test
    void sourceNormalizesIdentityAndProtectsRequiredText() {
        JobSource source = new JobSource(JobSourceProvider.LEVER, LeverRegion.GLOBAL,
                "  Example-Site  ", "  Example Company  ", true);

        assertEquals("example-site", source.sourceKey());
        assertEquals("Example Company", source.companyName());
        assertNull(source.lastSuccessfulSyncAt());
        assertThrows(IllegalArgumentException.class, () -> new JobSource(
                JobSourceProvider.LEVER, LeverRegion.GLOBAL, " ", "Company", true));
        assertThrows(IllegalArgumentException.class, () -> new JobSource(
                JobSourceProvider.LEVER, LeverRegion.GLOBAL, "\tsite\t", "Company", true));
        assertThrows(IllegalArgumentException.class, () -> new JobSource(
                JobSourceProvider.LEVER, LeverRegion.GLOBAL, "site", "bad\ncompany", true));
    }

    @Test
    void sourceSuccessfulSyncCannotMoveBackwards() {
        JobSource source = new JobSource(JobSourceProvider.LEVER, LeverRegion.EU,
                "site", "Company", false);
        assertThrows(IllegalStateException.class, () -> source.recordSuccessfulSync(START));
        assertNull(source.lastSuccessfulSyncAt());

        source.onCreate();
        LocalDateTime firstCompletion = source.createdAt().plusMinutes(1).withNano(123_456_999);
        LocalDateTime secondCompletion = firstCompletion.plusMinutes(1);
        source.recordSuccessfulSync(firstCompletion);
        source.recordSuccessfulSync(secondCompletion);

        assertEquals(DiscoveryTimestamps.toDatabasePrecision(secondCompletion, "secondCompletion"),
                source.lastSuccessfulSyncAt());
        assertThrows(IllegalArgumentException.class, () -> source.recordSuccessfulSync(firstCompletion));
        assertEquals(DiscoveryTimestamps.toDatabasePrecision(secondCompletion, "secondCompletion"),
                source.lastSuccessfulSyncAt());
    }

    @Test
    void listingBoundsTextAndProtectsTimestampOrder() {
        JobSource source = source();
        ExternalJobListing listing = new ExternalJobListing(source, mock(Job.class), " posting-1 ",
                ListingAvailability.LIVE, " sha256:test ", " https://jobs.example/1 ",
                "https://jobs.example/1/apply", START);

        assertEquals("posting-1", listing.externalJobId());
        assertEquals("sha256:test", listing.providerContentDigest());
        listing.recordVerification(ListingAvailability.CLOSED, START, START.plusHours(1));
        assertEquals(ListingAvailability.CLOSED, listing.availability());
        assertEquals(START.plusHours(1), listing.lastVerifiedAt());

        assertThrows(IllegalArgumentException.class, () -> listing.recordVerification(
                ListingAvailability.LIVE, START.minusMinutes(1), START.plusHours(2)));
        assertEquals(ListingAvailability.CLOSED, listing.availability());
        assertEquals(START, listing.lastSeenAt());
        assertEquals(START.plusHours(1), listing.lastVerifiedAt());
        assertThrows(IllegalArgumentException.class, () -> new ExternalJobListing(source, mock(Job.class),
                "posting", ListingAvailability.LIVE, "digest", " ", null, START));
        assertThrows(IllegalArgumentException.class, () -> new ExternalJobListing(source, mock(Job.class),
                "\tposting\t", ListingAvailability.LIVE, "digest", null, null, START));
    }

    @Test
    void listingLiveContentAndExtractionMutationsValidateBeforeChangingManagedState() {
        ExternalJobListing listing = new ExternalJobListing(source(), mock(Job.class), "posting",
                ListingAvailability.CLOSED, "old-digest", "https://old.example/job", null, START);
        listing.recordSuccessfulExtraction("jc005-v1:sha256:" + "a".repeat(64));

        listing.recordLiveContent("new-digest", "https://new.example/job", "https://new.example/apply",
                START.plusMinutes(1));
        assertEquals(ListingAvailability.LIVE, listing.availability());
        assertEquals("new-digest", listing.providerContentDigest());
        assertEquals("https://new.example/job", listing.hostedJobUrl());
        assertEquals(START.plusMinutes(1), listing.lastSeenAt());
        assertEquals("jc005-v1:sha256:" + "a".repeat(64), listing.extractionFingerprint());

        assertThrows(IllegalArgumentException.class, () -> listing.recordLiveContent(
                "rejected", " ", null, START.plusMinutes(2)));
        assertEquals("new-digest", listing.providerContentDigest());
        assertEquals("https://new.example/job", listing.hostedJobUrl());
        assertEquals(START.plusMinutes(1), listing.lastSeenAt());
        assertThrows(IllegalArgumentException.class, () -> listing.recordSuccessfulExtraction(" "));
        assertEquals("jc005-v1:sha256:" + "a".repeat(64), listing.extractionFingerprint());
    }

    @Test
    void synchronizationRunHasControlledTerminalTransitionsAndSafeFailureCodes() {
        JobSourceSyncRun successful = new JobSourceSyncRun(
                source(), JobSourceSyncTrigger.MANUAL, START, START.plusMinutes(5));
        assertEquals(START.plusMinutes(5), successful.leaseExpiresAt());
        successful.renewLease(START.plusMinutes(6));
        JobSourceSyncCounters counters = new JobSourceSyncCounters(8, 2, 1, 4, 1, 0, 6, 2);
        successful.succeed(START.plusMinutes(1), counters);

        assertEquals(JobSourceSyncStatus.SUCCEEDED, successful.status());
        assertEquals(counters, successful.counters());
        assertNull(successful.failureCode());
        assertNull(successful.leaseExpiresAt());
        assertThrows(IllegalStateException.class, () -> successful.renewLease(START.plusMinutes(7)));
        assertThrows(IllegalStateException.class,
                () -> successful.fail(START.plusMinutes(2), "PROVIDER_FAILED", counters));

        JobSourceSyncRun failed = new JobSourceSyncRun(source(), JobSourceSyncTrigger.SCHEDULED, START);
        failed.fail(START.plusMinutes(1), "PROVIDER_FAILED", JobSourceSyncCounters.zero());
        assertEquals(JobSourceSyncStatus.FAILED, failed.status());
        assertEquals("PROVIDER_FAILED", failed.failureCode());
        assertNull(failed.leaseExpiresAt());

        JobSourceSyncRun invalid = new JobSourceSyncRun(source(), JobSourceSyncTrigger.MANUAL, START);
        assertThrows(IllegalArgumentException.class,
                () -> invalid.fail(START.plusMinutes(1), "unsafe provider body", JobSourceSyncCounters.zero()));
        assertThrows(NullPointerException.class, () -> invalid.succeed(START.plusMinutes(1), null));
        assertEquals(JobSourceSyncStatus.RUNNING, invalid.status());
        assertNull(invalid.completedAt());
        assertNull(invalid.failureCode());
        assertEquals(JobSourceSyncCounters.zero(), invalid.counters());
        assertThrows(IllegalArgumentException.class,
                () -> new JobSourceSyncCounters(-1, 0, 0, 0, 0, 0, 0, 0));
        assertThrows(NullPointerException.class, () -> new JobSourceSyncRun(
                source(), JobSourceSyncTrigger.MANUAL, START, null));
        assertThrows(IllegalArgumentException.class, () -> new JobSourceSyncRun(
                source(), JobSourceSyncTrigger.MANUAL, START, START));
    }

    private static JobSource source() {
        return new JobSource(JobSourceProvider.LEVER, LeverRegion.GLOBAL, "site", "Company", true);
    }
}
