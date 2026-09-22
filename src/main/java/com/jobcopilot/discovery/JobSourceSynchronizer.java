package com.jobcopilot.discovery;

import com.jobcopilot.discovery.DiscoverySyncTransactions.MutableCounters;
import com.jobcopilot.job.DiscoveryJobWriter;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;

/** Shared run, lease, persistence, extraction and complete-only reconciliation lifecycle. */
abstract class JobSourceSynchronizer {
    private static final Logger log = LoggerFactory.getLogger(JobSourceSynchronizer.class);
    private final DiscoverySyncTransactions transactions;
    private final DiscoveryJobWriter jobs;
    private final JobSourceSyncHeartbeat heartbeat;
    private final Clock clock;

    JobSourceSynchronizer(DiscoverySyncTransactions transactions, DiscoveryJobWriter jobs,
            JobSourceSyncHeartbeat heartbeat, Clock clock) {
        this.transactions = transactions;
        this.jobs = jobs;
        this.heartbeat = heartbeat;
        this.clock = clock;
    }

    abstract JobSourceProvider provider();
    abstract Cursor cursor(JobSource source, String extractorVersion);

    interface Cursor { Step next(Runnable beforeProvider); }
    sealed interface Step permits Page, Failure {}
    record Page(List<MappedExternalJobListing> listings, boolean complete) implements Step {
        Page { listings = List.copyOf(listings); }
    }
    record Failure(String code) implements Step {}

    public final JobSourceSyncResult synchronize(long sourceId, JobSourceSyncTrigger trigger) {
        if (sourceId <= 0) throw new IllegalArgumentException("sourceId must be positive");
        if (trigger == null) throw new IllegalArgumentException("trigger is required");
        if (trigger == JobSourceSyncTrigger.SCHEDULED) return synchronizeScheduled(sourceId)
                .orElseThrow(() -> new JobSourceSyncAlreadyRunningException(sourceId));
        DiscoverySyncTransactions.Start start;
        try { start = transactions.startManual(sourceId); }
        catch (DataIntegrityViolationException race) {
            if (!hasConstraint(race, "ux_job_source_sync_runs_one_running_per_source")) throw race;
            throw new JobSourceSyncAlreadyRunningException(sourceId);
        }
        return execute(sourceId, start, JobSourceSyncTrigger.MANUAL, now());
    }

    public final Optional<JobSourceSyncResult> synchronizeScheduled(long sourceId) {
        if (sourceId <= 0) throw new IllegalArgumentException("sourceId must be positive");
        DiscoverySyncTransactions.ScheduledStartDecision decision;
        try { decision = transactions.startScheduled(sourceId); }
        catch (DataIntegrityViolationException race) {
            if (!hasConstraint(race, "ux_job_source_sync_runs_one_running_per_source")) throw race;
            return Optional.empty();
        }
        if (!decision.started()) return Optional.empty();
        if (decision.recoveredExpiredRun())
            log.warn("Discovery recovered expired run sourceId={} provider={}", sourceId, provider());
        return Optional.of(execute(sourceId, decision.start(), JobSourceSyncTrigger.SCHEDULED, now()));
    }

    private JobSourceSyncResult execute(long sourceId, DiscoverySyncTransactions.Start start,
            JobSourceSyncTrigger trigger, LocalDateTime observedAt) {
        long runId = start.runId();
        long wallStarted = System.nanoTime();
        MutableCounters counters = new MutableCounters();
        Set<String> seen = new HashSet<>();
        heartbeat.register(runId);
        try {
            if (start.source().provider() != provider())
                return fail(sourceId, runId, trigger, "POSTING_MAPPING_FAILED", counters, wallStarted);
            Cursor cursor;
            try { cursor = cursor(start.source(), jobs.extractorVersion()); }
            catch (RuntimeException setupFailure) {
                return fail(sourceId, runId, trigger, "POSTING_MAPPING_FAILED", counters, wallStarted);
            }
            while (true) {
                Step step;
                try { step = cursor.next(() -> transactions.renewBeforeProvider(sourceId, runId)); }
                catch (RuntimeException unexpected) {
                    if (unexpected instanceof JobSourceSyncLeaseLostException lost) throw lost;
                    return fail(sourceId, runId, trigger, "CONNECTION_FAILURE", counters, wallStarted);
                }
                if (step instanceof Failure failure)
                    return fail(sourceId, runId, trigger, failure.code(), counters, wallStarted);
                Page page = (Page) step;
                Set<String> pageIds = new HashSet<>();
                for (MappedExternalJobListing listing : page.listings()) {
                    if (!pageIds.add(listing.externalJobId()))
                        return fail(sourceId, runId, trigger, "POSTING_MAPPING_FAILED", counters, wallStarted);
                    if (seen.contains(listing.externalJobId()))
                        return fail(sourceId, runId, trigger,
                                "DUPLICATE_EXTERNAL_ID_DURING_TRAVERSAL", counters, wallStarted);
                }
                DiscoverySyncTransactions.PageResult persisted;
                try { persisted = transactions.persistPage(sourceId, runId, page.listings(), observedAt); }
                catch (RuntimeException persistenceFailure) {
                    if (persistenceFailure instanceof JobSourceSyncLeaseLostException lost) throw lost;
                    return fail(sourceId, runId, trigger, "PERSISTENCE_FAILURE", counters, wallStarted);
                }
                seen.addAll(pageIds);
                counters.discovered += page.listings().size();
                counters.created += persisted.created();
                counters.updated += persisted.updated();
                counters.unchanged += persisted.unchanged();
                counters.reopened += persisted.reopened();
                for (DiscoverySyncTransactions.Observation observation : persisted.observations()) {
                    boolean ready = observation.rankingReady();
                    if (observation.extractionNeeded()) {
                        try { ready = transactions.extract(sourceId, runId, observation.listingId(),
                                observation.fingerprint()); }
                        catch (RuntimeException extractionFailure) {
                            if (extractionFailure instanceof JobSourceSyncLeaseLostException lost) throw lost;
                            ready = false;
                            log.warn("Discovery extraction failed provider={} sourceId={} syncRunId={} listingId={}",
                                    provider(), sourceId, runId, observation.listingId());
                        }
                    }
                    if (ready) counters.rankingReady++; else counters.unready++;
                }
                if (page.complete()) {
                    try {
                        JobSourceSyncResult result = transactions.succeed(sourceId, runId,
                                new CompletedTraversal(seen), observedAt, counters);
                        terminalLog(sourceId, trigger, result, wallStarted);
                        return result;
                    } catch (RuntimeException finalizationFailure) {
                        if (finalizationFailure instanceof JobSourceSyncLeaseLostException lost) throw lost;
                        return fail(sourceId, runId, trigger, "PERSISTENCE_FAILURE", counters, wallStarted);
                    }
                }
            }
        } finally { heartbeat.unregister(runId); }
    }

    private JobSourceSyncResult fail(long sourceId, long runId, JobSourceSyncTrigger trigger,
            String code, MutableCounters counters, long wallStarted) {
        JobSourceSyncResult result = transactions.fail(sourceId, runId, code, counters);
        terminalLog(sourceId, trigger, result, wallStarted);
        return result;
    }

    private void terminalLog(long sourceId, JobSourceSyncTrigger trigger, JobSourceSyncResult result, long started) {
        log.info("Discovery sync completed provider={} sourceId={} syncRunId={} trigger={} status={} failureCode={} "
                        + "discovered={} created={} updated={} unchanged={} closed={} reopened={} "
                        + "rankingReady={} unready={} durationMs={}",
                provider(), sourceId, result.runId(), trigger, result.status(), result.failureCode(),
                result.counters().discovered(), result.counters().created(), result.counters().updated(),
                result.counters().unchanged(), result.counters().closed(), result.counters().reopened(),
                result.counters().rankingReady(), result.counters().unready(),
                Duration.ofNanos(Math.max(0, System.nanoTime() - started)).toMillis());
    }

    private LocalDateTime now() {
        return DiscoveryTimestamps.toDatabasePrecision(LocalDateTime.now(clock), "now");
    }

    private static boolean hasConstraint(Throwable failure, String expected) {
        for (Throwable current = failure; current != null; current = current.getCause())
            if (current instanceof ConstraintViolationException constraint
                    && expected.equalsIgnoreCase(constraint.getConstraintName())) return true;
        return false;
    }
}
