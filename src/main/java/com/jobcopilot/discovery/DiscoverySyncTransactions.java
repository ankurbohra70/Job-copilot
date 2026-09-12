package com.jobcopilot.discovery;

import com.jobcopilot.job.DiscoveryJobWriter;
import com.jobcopilot.job.Job;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
class DiscoverySyncTransactions {
    private final JobSourceRepository sources;
    private final ExternalJobListingRepository listings;
    private final JobSourceSyncRunRepository runs;
    private final DiscoveryJobWriter jobs;

    DiscoverySyncTransactions(JobSourceRepository sources, ExternalJobListingRepository listings,
            JobSourceSyncRunRepository runs, DiscoveryJobWriter jobs) {
        this.sources = sources;
        this.listings = listings;
        this.runs = runs;
        this.jobs = jobs;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    Start start(long sourceId, JobSourceSyncTrigger trigger, LocalDateTime startedAt) {
        JobSource source = source(sourceId);
        if (!source.enabled()) throw new IllegalStateException("Job source " + sourceId + " is disabled");
        if (source.provider() != JobSourceProvider.LEVER) {
            throw new IllegalArgumentException("Job source " + sourceId + " is not a Lever source");
        }
        if (runs.findByJobSourceAndStatus(source, JobSourceSyncStatus.RUNNING).isPresent()) {
            throw new JobSourceSyncAlreadyRunningException(sourceId);
        }
        JobSourceSyncRun run = runs.saveAndFlush(new JobSourceSyncRun(source, trigger, startedAt));
        return new Start(source, run.id());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    PageResult persistPage(long sourceId, List<LeverMappedListing> mapped, LocalDateTime observedAt) {
        JobSource source = source(sourceId);
        int created = 0;
        int updated = 0;
        int unchanged = 0;
        int reopened = 0;
        List<Observation> observations = new ArrayList<>(mapped.size());
        for (LeverMappedListing value : mapped) {
            ExternalJobListing listing = listings.findByJobSourceAndExternalJobId(source, value.externalJobId())
                    .orElse(null);
            if (listing == null) {
                Job job = jobs.create(value.jobProjection());
                listing = new ExternalJobListing(source, job, value.externalJobId(), ListingAvailability.LIVE,
                        value.providerContentDigest(), value.hostedJobUrl(), value.applyUrl(), observedAt);
                listings.save(listing);
                created++;
            } else {
                boolean wasClosed = listing.availability() == ListingAvailability.CLOSED;
                boolean providerChanged = !listing.providerContentDigest().equals(value.providerContentDigest())
                        || !Objects.equals(listing.hostedJobUrl(), value.hostedJobUrl())
                        || !Objects.equals(listing.applyUrl(), value.applyUrl());
                boolean jobChanged = jobs.update(listing.job(), value.jobProjection());
                listing.recordLiveContent(value.providerContentDigest(), value.hostedJobUrl(), value.applyUrl(),
                        observedAt);
                if (wasClosed || providerChanged || jobChanged) updated++; else unchanged++;
                if (wasClosed) reopened++;
            }
            listings.flush();
            boolean current = Objects.equals(listing.extractionFingerprint(), value.extractionFingerprint());
            observations.add(new Observation(listing.id(), value.extractionFingerprint(),
                    value.extractionFingerprint() != null && !current,
                    value.extractionFingerprint() != null && current && jobs.rankingReady(listing.job())));
        }
        return new PageResult(created, updated, unchanged, reopened, List.copyOf(observations));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    boolean extract(long listingId, String expectedFingerprint) {
        ExternalJobListing listing = listings.findById(listingId)
                .orElseThrow(() -> new IllegalStateException("External listing disappeared during extraction"));
        if (expectedFingerprint == null || Objects.equals(listing.extractionFingerprint(), expectedFingerprint)) {
            return expectedFingerprint != null && jobs.rankingReady(listing.job());
        }
        DiscoveryJobWriter.ProcessingResult outcome = jobs.processRequirements(listing.job());
        if (outcome instanceof DiscoveryJobWriter.ProcessingResult.Failed) return false;
        var processed = (DiscoveryJobWriter.ProcessingResult.Processed) outcome;
        listing.recordSuccessfulExtraction(expectedFingerprint);
        listings.saveAndFlush(listing);
        return processed.requirements().rankingReady();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    JobSourceSyncResult succeed(long sourceId, long runId, Set<String> seenIds, LocalDateTime observedAt,
            LocalDateTime completedAt, MutableCounters counters) {
        JobSource source = source(sourceId);
        int closed = 0;
        for (ExternalJobListing listing : listings.findByJobSource(source)) {
            if (seenIds.contains(listing.externalJobId())) continue;
            if (listing.availability() == ListingAvailability.LIVE) closed++;
            listing.recordVerification(ListingAvailability.CLOSED, listing.lastSeenAt(), observedAt);
        }
        JobSourceSyncCounters finalCounters = counters.freeze(closed);
        JobSourceSyncRun run = runningRun(runId);
        run.succeed(completedAt, finalCounters);
        source.recordSuccessfulSync(completedAt);
        runs.save(run);
        sources.save(source);
        return result(run, finalCounters);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    JobSourceSyncResult fail(long runId, String failureCode, LocalDateTime completedAt,
            MutableCounters counters) {
        JobSourceSyncRun run = runningRun(runId);
        if (completedAt.isBefore(run.startedAt())) completedAt = run.startedAt();
        JobSourceSyncCounters finalCounters = counters.freeze();
        run.fail(completedAt, failureCode, finalCounters);
        runs.saveAndFlush(run);
        return result(run, finalCounters);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    int recover(LocalDateTime completedAt) {
        List<JobSourceSyncRun> stale = runs.findAllByStatus(JobSourceSyncStatus.RUNNING);
        for (JobSourceSyncRun run : stale) {
            LocalDateTime terminalAt = completedAt.isBefore(run.startedAt()) ? run.startedAt() : completedAt;
            run.abandon(terminalAt, "APPLICATION_RESTARTED", JobSourceSyncCounters.zero());
        }
        runs.saveAll(stale);
        return stale.size();
    }

    private JobSource source(long sourceId) {
        return sources.findById(sourceId)
                .orElseThrow(() -> new IllegalArgumentException("Job source " + sourceId + " was not found"));
    }

    private JobSourceSyncRun runningRun(long runId) {
        JobSourceSyncRun run = runs.findById(runId)
                .orElseThrow(() -> new IllegalStateException("Synchronization run " + runId + " was not found"));
        if (run.status() != JobSourceSyncStatus.RUNNING) {
            throw new IllegalStateException("Synchronization run " + runId + " is not running");
        }
        return run;
    }

    private static JobSourceSyncResult result(JobSourceSyncRun run, JobSourceSyncCounters counters) {
        return new JobSourceSyncResult(run.id(), run.status(), run.failureCode(), new JobSourceSyncResult.Counters(
                counters.discovered(), counters.created(), counters.updated(), counters.unchanged(), counters.closed(),
                counters.reopened(), counters.rankingReady(), counters.unready()));
    }

    record Start(JobSource source, long runId) {}
    record Observation(long listingId, String fingerprint, boolean extractionNeeded, boolean rankingReady) {}
    record PageResult(int created, int updated, int unchanged, int reopened,
            List<Observation> observations) {}

    static final class MutableCounters {
        int discovered;
        int created;
        int updated;
        int unchanged;
        int reopened;
        int rankingReady;
        int unready;

        JobSourceSyncCounters freeze() {
            return new JobSourceSyncCounters(discovered, created, updated, unchanged, 0, reopened,
                    rankingReady, unready);
        }

        JobSourceSyncCounters freeze(int closed) {
            return new JobSourceSyncCounters(discovered, created, updated, unchanged, closed, reopened,
                    rankingReady, unready);
        }
    }
}
