package com.jobcopilot.discovery;

import com.jobcopilot.job.JobStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

@Service
public class LiveOpportunityReadService {
    private final ExternalJobListingRepository listings;
    private final JobVerificationService verification;

    LiveOpportunityReadService(ExternalJobListingRepository listings, JobVerificationService verification) {
        this.listings = listings;
        this.verification = verification;
    }

    @Transactional(readOnly = true)
    public LiveOpportunityListingSet find(Set<JobStatus> statuses) {
        var live = listings.findLiveOpportunityProjections(statuses);
        var current = live.stream()
                .filter(verification::isCurrent)
                .map(row -> new LiveOpportunityListingSnapshot(row.listingId(), row.sourceId(),
                        row.externalJobId(), row.jobId(), row.hostedJobUrl(), row.applyUrl(),
                        row.lastVerifiedAt()))
                .toList();
        return new LiveOpportunityListingSet(live.size(), current);
    }

}
