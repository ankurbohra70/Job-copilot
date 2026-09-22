package com.jobcopilot.discovery;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
class JobSourceSyncDispatcher {
    private final JobSourceRepository sources;
    private final Map<JobSourceProvider, JobSourceSynchronizer> adapters;

    JobSourceSyncDispatcher(JobSourceRepository sources, List<JobSourceSynchronizer> synchronizers) {
        this.sources = sources;
        EnumMap<JobSourceProvider, JobSourceSynchronizer> mapped = new EnumMap<>(JobSourceProvider.class);
        for (JobSourceSynchronizer synchronizer : synchronizers)
            if (mapped.putIfAbsent(synchronizer.provider(), synchronizer) != null)
                throw new IllegalStateException("Duplicate provider synchronizer: " + synchronizer.provider());
        for (JobSourceProvider provider : JobSourceProvider.values())
            if (!mapped.containsKey(provider))
                throw new IllegalStateException("Missing provider synchronizer: " + provider);
        adapters = Map.copyOf(mapped);
    }

    JobSourceSyncResult synchronize(long sourceId, JobSourceSyncTrigger trigger) {
        return adapter(sourceId).synchronize(sourceId, trigger);
    }

    Optional<JobSourceSyncResult> synchronizeScheduled(long sourceId) {
        return adapter(sourceId).synchronizeScheduled(sourceId);
    }

    private JobSourceSynchronizer adapter(long sourceId) {
        JobSource source = sources.findById(sourceId)
                .orElseThrow(() -> new IllegalArgumentException("Job source " + sourceId + " was not found"));
        JobSourceSynchronizer adapter = adapters.get(source.provider());
        if (adapter == null) throw new IllegalArgumentException("Unsupported provider");
        return adapter;
    }
}
