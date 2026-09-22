package com.jobcopilot.discovery;

import com.jobcopilot.discovery.lever.LeverFetchResult;
import com.jobcopilot.discovery.lever.LeverPageRequest;
import com.jobcopilot.discovery.lever.LeverPosting;
import com.jobcopilot.discovery.lever.LeverPostingGateway;
import com.jobcopilot.discovery.lever.LeverRegion;
import com.jobcopilot.discovery.lever.LeverSource;
import com.jobcopilot.job.DiscoveryJobWriter;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class LeverJobSourceSynchronizer extends JobSourceSynchronizer {
    static final int PAGE_LIMIT = 100;
    static final int MAX_PAGES = 100;
    private final LeverPostingGateway gateway;
    private final LeverPostingMapper mapper;

    LeverJobSourceSynchronizer(LeverPostingGateway gateway, LeverPostingMapper mapper,
            DiscoverySyncTransactions transactions, DiscoveryJobWriter jobs,
            JobSourceSyncHeartbeat heartbeat, Clock clock) {
        super(transactions, jobs, heartbeat, clock);
        this.gateway = gateway;
        this.mapper = mapper;
    }

    @Override JobSourceProvider provider() { return JobSourceProvider.LEVER; }

    @Override Cursor cursor(JobSource source, String extractorVersion) {
        LeverSource leverSource = new LeverSource(LeverRegion.valueOf(source.region().name()), source.sourceKey());
        return new Cursor() {
            int skip;
            int pageIndex;

            @Override public Step next(Runnable beforeProvider) {
                if (pageIndex >= MAX_PAGES) return new Failure("TRAVERSAL_LIMIT_EXCEEDED");
                LeverPageRequest request = new LeverPageRequest(skip, PAGE_LIMIT);
                beforeProvider.run();
                LeverFetchResult fetched = gateway.fetchPage(leverSource, request);
                if (fetched instanceof LeverFetchResult.Failure failure) return new Failure(failure.kind().name());
                List<LeverPosting> postings = ((LeverFetchResult.Success) fetched).postings();
                List<MappedExternalJobListing> mapped = new ArrayList<>(postings.size());
                try {
                    for (LeverPosting posting : postings) mapped.add(mapper.map(source, posting, extractorVersion));
                } catch (IllegalArgumentException invalid) { return new Failure("POSTING_MAPPING_FAILED"); }
                pageIndex++;
                skip += postings.size();
                return new Page(mapped, postings.size() < PAGE_LIMIT);
            }
        };
    }
}
