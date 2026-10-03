package com.jobcopilot.job;

import java.util.Collection;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Bounded read seam for discovery listing readiness. */
@Service
public class DiscoveryJobReadService {
    private final JobRepository jobs;

    DiscoveryJobReadService(JobRepository jobs) {
        this.jobs = jobs;
    }

    @Transactional(readOnly = true)
    public Set<Long> idsWithSkills(Collection<Long> jobIds) {
        return jobIds.isEmpty() ? Set.of() : Set.copyOf(jobs.findIdsWithSkills(jobIds));
    }
}
