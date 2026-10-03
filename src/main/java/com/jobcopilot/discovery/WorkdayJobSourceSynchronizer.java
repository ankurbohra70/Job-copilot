package com.jobcopilot.discovery;

import com.jobcopilot.discovery.workday.WorkdayDetailRequest;
import com.jobcopilot.discovery.workday.WorkdayFetchResult;
import com.jobcopilot.discovery.workday.WorkdayPageRequest;
import com.jobcopilot.discovery.workday.WorkdayPostingGateway;
import com.jobcopilot.discovery.workday.WorkdaySource;
import com.jobcopilot.discovery.workday.WorkdayTraversal;
import com.jobcopilot.job.DiscoveryJobWriter;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class WorkdayJobSourceSynchronizer extends JobSourceSynchronizer {
    private final WorkdayPostingGateway gateway;
    private final WorkdayPostingMapper mapper;

    WorkdayJobSourceSynchronizer(WorkdayPostingGateway gateway, WorkdayPostingMapper mapper,
            DiscoverySyncTransactions transactions, DiscoveryJobWriter jobs,
            JobSourceSyncHeartbeat heartbeat, Clock clock) {
        super(transactions, jobs, heartbeat, clock);
        this.gateway = gateway;
        this.mapper = mapper;
    }

    @Override JobSourceProvider provider() { return JobSourceProvider.WORKDAY; }

    @Override Cursor cursor(JobSource source, String extractorVersion) {
        WorkdaySource workday = new WorkdaySource(source.endpointHost(), source.tenantKey(),
                source.siteKey(), source.locale());
        return new Cursor() {
            WorkdayTraversal.Cursor traversal;

            @Override public Step next(Runnable beforeProvider) {
                if (traversal == null) {
                    WorkdayPostingGateway fencedGateway = new WorkdayPostingGateway() {
                        @Override public WorkdayFetchResult searchPage(WorkdaySource requested,
                                WorkdayPageRequest page) {
                            beforeProvider.run();
                            return gateway.searchPage(requested, page);
                        }
                        @Override public WorkdayFetchResult fetchDetail(WorkdaySource requested,
                                WorkdayDetailRequest detail) {
                            beforeProvider.run();
                            return gateway.fetchDetail(requested, detail);
                        }
                    };
                    traversal = new WorkdayTraversal(fencedGateway).cursor(workday);
                }
                WorkdayTraversal.Step step = traversal.next();
                if (step instanceof WorkdayTraversal.Incomplete incomplete)
                    return new Failure(incomplete.failureCode());
                WorkdayTraversal.Page page = (WorkdayTraversal.Page) step;
                List<MappedExternalJobListing> mapped = new ArrayList<>();
                try {
                    for (WorkdayTraversal.Observation observation : page.observations())
                        mapped.add(mapper.map(source, observation, extractorVersion));
                } catch (IllegalArgumentException invalid) { return new Failure("POSTING_MAPPING_FAILED"); }
                return new Page(mapped, page.complete());
            }
        };
    }
}
