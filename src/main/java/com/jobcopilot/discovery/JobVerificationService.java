package com.jobcopilot.discovery;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class JobVerificationService {
    private final ExternalJobListingRepository listings;
    private final ExtractionFingerprint fingerprints;
    private final com.jobcopilot.job.JobRequirementExtractor extractor;
    JobVerificationService(ExternalJobListingRepository listings, ExtractionFingerprint fingerprints,
            com.jobcopilot.job.JobRequirementExtractor extractor) {
        this.listings = listings; this.fingerprints = fingerprints; this.extractor = extractor;
    }
    @Transactional(readOnly = true)
    public JobVerificationSnapshot find(Long jobId) {
        return listings.findReadProjectionByJobId(jobId)
                .map(p -> new JobVerificationSnapshot(p.listingId(), p.availability(), p.hostedJobUrl(),
                        p.applyUrl(), extractionState(p), p.lastVerifiedAt()))
                .orElse(null);
    }
    private ExtractionState extractionState(ExternalJobListingReadProjection row) {
        if (row.description() == null || row.description().isBlank()) return ExtractionState.UNAVAILABLE;
        if (row.extractionFingerprint() == null) return ExtractionState.PENDING;
        String current = fingerprints.calculate(extractor.version(), row.description());
        return current.equals(row.extractionFingerprint()) ? ExtractionState.CURRENT : ExtractionState.STALE;
    }
}
