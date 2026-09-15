package com.jobcopilot.discovery;

import com.jobcopilot.job.JobStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LiveOpportunityReadServiceTest {
    @Test
    void retainsProviderNeutralLiveCountAndExposesOnlyCurrentExtractions() {
        ExternalJobListingRepository listings = mock(ExternalJobListingRepository.class);
        JobVerificationService verification = mock(JobVerificationService.class);
        Set<JobStatus> statuses = Set.of(JobStatus.DISCOVERED, JobStatus.SHORTLISTED);
        ExternalJobListingReadProjection current = projection(1L, 11L);
        ExternalJobListingReadProjection stale = projection(2L, 12L);
        when(listings.findLiveOpportunityProjections(statuses)).thenReturn(List.of(current, stale));
        when(verification.isCurrent(current)).thenReturn(true);
        when(verification.isCurrent(stale)).thenReturn(false);

        LiveOpportunityListingSet result = new LiveOpportunityReadService(listings, verification).find(statuses);

        assertEquals(2, result.liveListingCount());
        assertEquals(List.of(11L), result.currentListings().stream()
                .map(LiveOpportunityListingSnapshot::jobId).toList());
        verify(listings).findLiveOpportunityProjections(statuses);
    }

    private static ExternalJobListingReadProjection projection(long listingId, long jobId) {
        LocalDateTime time = LocalDateTime.of(2026, 9, 15, 12, 0);
        return new ExternalJobListingReadProjection(listingId, 3L, "external-" + jobId,
                ListingAvailability.LIVE, jobId, JobStatus.DISCOVERED, "Java", null,
                "https://jobs.example.test/" + jobId, "https://apply.example.test/" + jobId,
                "fingerprint", time, time, time);
    }
}
