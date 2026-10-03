package com.jobcopilot.discovery;

import com.jobcopilot.job.JobStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public record ExternalJobListingReadProjection(
        Long listingId,
        Long sourceId,
        String externalJobId,
        ListingAvailability availability,
        Long jobId,
        JobStatus jobStatus,
        String description,
        BigDecimal minYearsExperience,
        String hostedJobUrl,
        String applyUrl,
        String extractionFingerprint,
        LocalDateTime firstSeenAt,
        LocalDateTime lastSeenAt,
        LocalDateTime lastVerifiedAt) {
}
