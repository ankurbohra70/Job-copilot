package com.jobcopilot.discovery;

import java.time.LocalDateTime;

public record JobVerificationSnapshot(Long listingId, ListingAvailability availability,
        String hostedJobUrl, String applyUrl, ExtractionState extractionState, LocalDateTime lastVerifiedAt) {
}
