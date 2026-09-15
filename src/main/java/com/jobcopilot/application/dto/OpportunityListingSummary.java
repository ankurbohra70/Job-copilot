package com.jobcopilot.application.dto;

import com.jobcopilot.discovery.LiveOpportunityListingSnapshot;
import java.time.LocalDateTime;

public record OpportunityListingSummary(Long listingId, Long sourceId, String externalJobId,
        String hostedJobUrl, String applyUrl, LocalDateTime lastVerifiedAt) {
    public static OpportunityListingSummary from(LiveOpportunityListingSnapshot value) {
        return new OpportunityListingSummary(value.listingId(), value.sourceId(), value.externalJobId(),
                value.hostedJobUrl(), value.applyUrl(), value.lastVerifiedAt());
    }
}
