package com.jobcopilot.discovery;

import com.jobcopilot.job.DiscoveryJobWriter;
import com.jobcopilot.job.Job;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
class DiscoverySyncTransactions {
    private final JobSourceRepository sources;
    private final ExternalJobListingRepository listings;
    private final JobSourceSyncRunRepository runs;
    private final DiscoveryJobWriter jobs;
    private final DiscoveryDatabaseTime databaseTime;
    private final DiscoverySchedulingProperties scheduling;
    private final NamedParameterJdbcTemplate jdbc;

    DiscoverySyncTransactions(JobSourceRepository sources, ExternalJobListingRepository listings,
            JobSourceSyncRunRepository runs, DiscoveryJobWriter jobs, DiscoveryDatabaseTime databaseTime,
            DiscoverySchedulingProperties scheduling, NamedParameterJdbcTemplate jdbc) {
        this.sources = sources;
        this.listings = listings;
        this.runs = runs;
        this.jobs = jobs;
        this.databaseTime = databaseTime;
        this.scheduling = scheduling;
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    Start startManual(long sourceId) {
        JobSource source = lockedSource(sourceId);
        validateSource(source, sourceId);
        JobSourceSyncRun active = runs.findRunningForUpdate(sourceId).orElse(null);
        LocalDateTime now = databaseTime.now();
        if (active != null) {
            if (active.leaseExpiresAt().isAfter(now)) throw new JobSourceSyncAlreadyRunningException(sourceId);
            abandonExpired(active, now);
        }
        return createRun(source, JobSourceSyncTrigger.MANUAL, now);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    ScheduledStartDecision startScheduled(long sourceId) {
        JobSource source = sources.findLockedById(sourceId).orElse(null);
        if (source == null || !source.enabled()) {
            return new ScheduledStartDecision(null, ScheduledSkipReason.DISABLED_OR_MISSING, false);
        }
        validateProvider(source, sourceId);
        JobSourceSyncRun active = runs.findRunningForUpdate(sourceId).orElse(null);
        LocalDateTime now = databaseTime.now();
        if (active != null) {
            if (active.leaseExpiresAt().isAfter(now)) {
                return new ScheduledStartDecision(null, ScheduledSkipReason.ACTIVE_RUN, false);
            }
            abandonExpired(active, now);
            return new ScheduledStartDecision(createRun(source, JobSourceSyncTrigger.SCHEDULED, now),
                    null, true);
        }
        List<JobSourceSyncRun> terminal = runs.findTerminalHistory(sourceId, PageRequest.of(0, 1));
        if (!terminal.isEmpty() && terminal.getFirst().completedAt().plus(scheduling.syncCadence()).isAfter(now)) {
            return new ScheduledStartDecision(null, ScheduledSkipReason.CADENCE, false);
        }
        return new ScheduledStartDecision(createRun(source, JobSourceSyncTrigger.SCHEDULED, now),
                null, false);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void renewBeforeProvider(long sourceId, long runId) {
        Authority authority = authority(sourceId, runId);
        renewEstablished(authority.run());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    Set<Long> heartbeat(Collection<Long> runIds) {
        if (runIds.isEmpty()) return Set.of();
        String sql = """
                with locked as materialized (
                    select id
                    from job_source_sync_runs
                    where id in (:runIds) and status = 'RUNNING'
                    for update skip locked
                ), db_now as materialized (
                    select clock_timestamp()::timestamp as value, count(*) as dependency
                    from locked
                )
                update job_source_sync_runs run
                set lease_expires_at = db_now.value + (:leaseMillis * interval '1 millisecond')
                from locked, db_now
                where run.id = locked.id
                  and run.status = 'RUNNING'
                  and run.lease_expires_at > db_now.value
                returning run.id
                """;
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("runIds", List.copyOf(runIds));
        parameters.put("leaseMillis", scheduling.leaseTimeout().toMillis());
        return Set.copyOf(jdbc.query(sql, parameters, (row, index) -> row.getLong(1)));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    PageResult persistPage(long sourceId, long runId, List<LeverMappedListing> mapped,
            LocalDateTime observedAt) {
        Authority authority = authority(sourceId, runId);
        JobSource source = authority.run().jobSource();
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
        renewEstablished(authority.run());
        return new PageResult(created, updated, unchanged, reopened, List.copyOf(observations));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    boolean extract(long sourceId, long runId, long listingId, String expectedFingerprint) {
        Authority authority = authority(sourceId, runId);
        ExternalJobListing listing = listings.findById(listingId)
                .orElseThrow(() -> new IllegalStateException("External listing disappeared during extraction"));
        if (!listing.jobSource().id().equals(sourceId)) throw new JobSourceSyncLeaseLostException(sourceId, runId);
        boolean ready;
        if (expectedFingerprint == null || Objects.equals(listing.extractionFingerprint(), expectedFingerprint)) {
            ready = expectedFingerprint != null && jobs.rankingReady(listing.job());
        } else {
            DiscoveryJobWriter.ProcessingResult outcome = jobs.processRequirements(listing.job());
            if (outcome instanceof DiscoveryJobWriter.ProcessingResult.Failed) {
                ready = false;
            } else {
                var processed = (DiscoveryJobWriter.ProcessingResult.Processed) outcome;
                listing.recordSuccessfulExtraction(expectedFingerprint);
                listings.saveAndFlush(listing);
                ready = processed.requirements().rankingReady();
            }
        }
        renewEstablished(authority.run());
        return ready;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    JobSourceSyncResult succeed(long sourceId, long runId, Set<String> seenIds, LocalDateTime observedAt,
            MutableCounters counters) {
        JobSource source = lockedSource(sourceId);
        JobSourceSyncRun run = authority(sourceId, runId).run();
        int closed = 0;
        for (ExternalJobListing listing : listings.findByJobSource(source)) {
            if (seenIds.contains(listing.externalJobId())) continue;
            if (listing.availability() == ListingAvailability.LIVE) closed++;
            listing.recordVerification(ListingAvailability.CLOSED, listing.lastSeenAt(), observedAt);
        }
        JobSourceSyncCounters finalCounters = counters.freeze(closed);
        LocalDateTime completedAt = databaseTime.now();
        if (completedAt.isBefore(run.startedAt())) completedAt = run.startedAt();
        run.succeed(completedAt, finalCounters);
        source.recordSuccessfulSync(completedAt);
        runs.save(run);
        sources.save(source);
        return result(run, finalCounters);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    JobSourceSyncResult fail(long sourceId, long runId, String failureCode, MutableCounters counters) {
        lockedSource(sourceId);
        JobSourceSyncRun run = authority(sourceId, runId).run();
        LocalDateTime completedAt = databaseTime.now();
        if (completedAt.isBefore(run.startedAt())) completedAt = run.startedAt();
        JobSourceSyncCounters finalCounters = counters.freeze();
        run.fail(completedAt, failureCode, finalCounters);
        runs.saveAndFlush(run);
        return result(run, finalCounters);
    }

    private JobSource lockedSource(long sourceId) {
        return sources.findLockedById(sourceId)
                .orElseThrow(() -> new IllegalArgumentException("Job source " + sourceId + " was not found"));
    }

    private void validateSource(JobSource source, long sourceId) {
        if (!source.enabled()) throw new IllegalStateException("Job source " + sourceId + " is disabled");
        validateProvider(source, sourceId);
    }

    private static void validateProvider(JobSource source, long sourceId) {
        if (source.provider() != JobSourceProvider.LEVER) {
            throw new IllegalArgumentException("Job source " + sourceId + " is not a Lever source");
        }
    }

    private Start createRun(JobSource source, JobSourceSyncTrigger trigger, LocalDateTime databaseNow) {
        JobSourceSyncRun run = runs.saveAndFlush(new JobSourceSyncRun(source, trigger, databaseNow,
                expiresAfter(databaseNow)));
        return new Start(source, run.id());
    }

    private void abandonExpired(JobSourceSyncRun run, LocalDateTime now) {
        LocalDateTime completedAt = now.isBefore(run.startedAt()) ? run.startedAt() : now;
        run.abandon(completedAt, "LEASE_EXPIRED", JobSourceSyncCounters.zero());
        runs.saveAndFlush(run);
    }

    private Authority authority(long sourceId, long runId) {
        JobSourceSyncRun run = runs.findLockedById(runId)
                .orElseThrow(() -> new JobSourceSyncLeaseLostException(sourceId, runId));
        LocalDateTime now = databaseTime.now();
        if (!run.jobSource().id().equals(sourceId) || run.status() != JobSourceSyncStatus.RUNNING
                || run.leaseExpiresAt() == null || !run.leaseExpiresAt().isAfter(now)) {
            throw new JobSourceSyncLeaseLostException(sourceId, runId);
        }
        return new Authority(run);
    }

    /** The caller already holds this exact run row from a valid authority check. */
    private void renewEstablished(JobSourceSyncRun run) {
        LocalDateTime expiresAt = expiresAfter(databaseTime.now());
        if (expiresAt.isAfter(run.leaseExpiresAt())) run.renewLease(expiresAt);
        runs.saveAndFlush(run);
    }

    private LocalDateTime expiresAfter(LocalDateTime now) {
        return DiscoveryTimestamps.toDatabasePrecision(now.plus(scheduling.leaseTimeout()), "leaseExpiresAt");
    }

    private static JobSourceSyncResult result(JobSourceSyncRun run, JobSourceSyncCounters counters) {
        return new JobSourceSyncResult(run.id(), run.status(), run.failureCode(), new JobSourceSyncResult.Counters(
                counters.discovered(), counters.created(), counters.updated(), counters.unchanged(), counters.closed(),
                counters.reopened(), counters.rankingReady(), counters.unready()));
    }

    record Start(JobSource source, long runId) {}
    enum ScheduledSkipReason { DISABLED_OR_MISSING, ACTIVE_RUN, CADENCE }
    record ScheduledStartDecision(Start start, ScheduledSkipReason skipReason, boolean recoveredExpiredRun) {
        boolean started() { return start != null; }
    }
    private record Authority(JobSourceSyncRun run) {}
    record Observation(long listingId, String fingerprint, boolean extractionNeeded, boolean rankingReady) {}
    record PageResult(int created, int updated, int unchanged, int reopened, List<Observation> observations) {}

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
