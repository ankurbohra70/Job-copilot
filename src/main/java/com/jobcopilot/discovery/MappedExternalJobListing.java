package com.jobcopilot.discovery;

import com.jobcopilot.job.DiscoveryJobWriter;

record MappedExternalJobListing(String externalJobId, String title, String company, String location,
        String hostedJobUrl, String applyUrl, String description, String providerContentDigest,
        String extractionFingerprint, JobSourceProvider provider) {
    DiscoveryJobWriter.Projection jobProjection() {
        return new DiscoveryJobWriter.Projection(title, company, location, hostedJobUrl,
                description, provider.name(), externalJobId);
    }
}
