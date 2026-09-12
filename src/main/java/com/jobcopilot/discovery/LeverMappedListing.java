package com.jobcopilot.discovery;

import com.jobcopilot.job.DiscoveryJobWriter;

record LeverMappedListing(String externalJobId, String title, String company, String location,
        String hostedJobUrl, String applyUrl, String description, String providerContentDigest,
        String extractionFingerprint) {
    DiscoveryJobWriter.Projection jobProjection() {
        return new DiscoveryJobWriter.Projection(title, company, location, hostedJobUrl,
                description, "LEVER", externalJobId);
    }
}
