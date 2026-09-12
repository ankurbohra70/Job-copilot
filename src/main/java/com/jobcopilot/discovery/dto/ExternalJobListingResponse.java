package com.jobcopilot.discovery.dto;

import com.jobcopilot.discovery.ExtractionState;
import com.jobcopilot.discovery.ListingAvailability;
import com.jobcopilot.job.JobStatus;
import java.time.LocalDateTime;

public record ExternalJobListingResponse(
        long listingId,
        long sourceId,
        String externalJobId,
        ListingAvailability availability,
        long jobId,
        JobStatus jobStatus,
        String hostedJobUrl,
        String applyUrl,
        ExtractionState extractionState,
        boolean rankingReady,
        LocalDateTime firstSeenAt,
        LocalDateTime lastSeenAt,
        LocalDateTime lastVerifiedAt) {
}
