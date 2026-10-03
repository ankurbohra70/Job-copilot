package com.jobcopilot.discovery;

import java.time.LocalDateTime;

public record LiveOpportunityListingSnapshot(
        Long listingId,
        Long sourceId,
        String externalJobId,
        Long jobId,
        String hostedJobUrl,
        String applyUrl,
        LocalDateTime lastVerifiedAt) {
}
