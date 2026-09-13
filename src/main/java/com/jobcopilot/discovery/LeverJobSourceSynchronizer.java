package com.jobcopilot.discovery;

import com.jobcopilot.discovery.DiscoverySyncTransactions.MutableCounters;
import com.jobcopilot.discovery.lever.LeverFetchResult;
import com.jobcopilot.discovery.lever.LeverPageRequest;
import com.jobcopilot.discovery.lever.LeverPosting;
import com.jobcopilot.discovery.lever.LeverPostingGateway;
import com.jobcopilot.discovery.lever.LeverSource;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
public class LeverJobSourceSynchronizer {
    static final int PAGE_LIMIT = 100;
    static final int MAX_PAGES = 100;
    private static final Logger log = LoggerFactory.getLogger(LeverJobSourceSynchronizer.class);

    private final LeverPostingGateway gateway;
    private final LeverPostingMapper mapper;
    private final DiscoverySyncTransactions transactions;
    private final com.jobcopilot.job.DiscoveryJobWriter jobWriter;
    private final JobSourceSyncHeartbeat heartbeat;
    private final Clock clock;

    LeverJobSourceSynchronizer(LeverPostingGateway gateway, LeverPostingMapper mapper,
            DiscoverySyncTransactions transactions, com.jobcopilot.job.DiscoveryJobWriter jobWriter,
            JobSourceSyncHeartbeat heartbeat, Clock clock) {
        this.gateway = gateway;
        this.mapper = mapper;
        this.transactions = transactions;
        this.jobWriter = jobWriter;
        this.heartbeat = heartbeat;
        this.clock = clock;
    }

    public JobSourceSyncResult synchronize(long sourceId, JobSourceSyncTrigger trigger) {
        if (sourceId <= 0) throw new IllegalArgumentException("sourceId must be positive");
        if (trigger == null) throw new IllegalArgumentException("trigger is required");
        if (trigger == JobSourceSyncTrigger.SCHEDULED) {
            return synchronizeScheduled(sourceId)
                    .orElseThrow(() -> new JobSourceSyncAlreadyRunningException(sourceId));
        }
        LocalDateTime observedAt = now();
        DiscoverySyncTransactions.Start start;
        try {
            start = transactions.startManual(sourceId);
        } catch (DataIntegrityViolationException race) {
            if (!hasConstraint(race, "ux_job_source_sync_runs_one_running_per_source")) throw race;
            throw new JobSourceSyncAlreadyRunningException(sourceId);
        }
        return execute(sourceId, start, JobSourceSyncTrigger.MANUAL, observedAt);
    }

    public Optional<JobSourceSyncResult> synchronizeScheduled(long sourceId) {
        if (sourceId <= 0) throw new IllegalArgumentException("sourceId must be positive");
        LocalDateTime observedAt = now();
        DiscoverySyncTransactions.ScheduledStartDecision decision;
        try {
            decision = transactions.startScheduled(sourceId);
        } catch (DataIntegrityViolationException race) {
            if (!hasConstraint(race, "ux_job_source_sync_runs_one_running_per_source")) throw race;
            log.debug("Scheduled discovery claim lost sourceId={} outcome=CONCURRENT_CLAIM", sourceId);
            return Optional.empty();
        }
        if (!decision.started()) {
            log.debug("Scheduled discovery claim skipped sourceId={} outcome={}", sourceId, decision.skipReason());
            return Optional.empty();
        }
        if (decision.recoveredExpiredRun()) {
            log.warn("Scheduled discovery recovered expired run sourceId={} outcome=LEASE_EXPIRED", sourceId);
        }
        return Optional.of(execute(sourceId, decision.start(), JobSourceSyncTrigger.SCHEDULED, observedAt));
    }

    private JobSourceSyncResult execute(long sourceId, DiscoverySyncTransactions.Start start,
            JobSourceSyncTrigger trigger,
            LocalDateTime observedAt) {
        long wallStarted = System.nanoTime();

        long runId = start.runId();
        heartbeat.register(runId);
        try {
            return traverse(sourceId, start, trigger, observedAt, wallStarted);
        } finally {
            heartbeat.unregister(runId);
        }
    }

    private JobSourceSyncResult traverse(long sourceId, DiscoverySyncTransactions.Start start,
            JobSourceSyncTrigger trigger, LocalDateTime observedAt, long wallStarted) {
        long runId = start.runId();
        MutableCounters counters = new MutableCounters();
        Set<String> seenIds = new HashSet<>();
        LeverSource leverSource = new LeverSource(start.source().region(), start.source().sourceKey());
        String extractorVersion = jobWriter.extractorVersion();
        int skip = 0;

        log.info("Lever sync started sourceId={} provider=LEVER region={} syncRunId={} trigger={}",
                sourceId, leverSource.region(), runId, trigger);

        for (int pageIndex = 0; pageIndex < MAX_PAGES; pageIndex++) {
            LeverPageRequest request = new LeverPageRequest(skip, PAGE_LIMIT);
            LeverFetchResult fetched;
            try {
                transactions.renewBeforeProvider(sourceId, runId);
                fetched = gateway.fetchPage(leverSource, request);
            } catch (RuntimeException unexpectedGatewayFailure) {
                if (unexpectedGatewayFailure instanceof JobSourceSyncLeaseLostException lost) throw lost;
                return fail(sourceId, runId, trigger, "CONNECTION_FAILURE", counters, wallStarted);
            }
            if (fetched instanceof LeverFetchResult.Failure failure) {
                return fail(sourceId, runId, trigger, failure.kind().name(), counters, wallStarted);
            }

            List<LeverPosting> postings = ((LeverFetchResult.Success) fetched).postings();
            List<LeverMappedListing> mapped = new ArrayList<>(postings.size());
            Set<String> pageIds = new HashSet<>();
            try {
                for (LeverPosting posting : postings) {
                    LeverMappedListing value = mapper.map(start.source(), posting, extractorVersion);
                    if (!pageIds.add(value.externalJobId())) {
                        return fail(sourceId, runId, trigger, "POSTING_MAPPING_FAILED", counters, wallStarted);
                    }
                    if (seenIds.contains(value.externalJobId())) {
                        return fail(sourceId, runId, trigger, "DUPLICATE_EXTERNAL_ID_DURING_TRAVERSAL",
                                counters, wallStarted);
                    }
                    mapped.add(value);
                }
            } catch (IllegalArgumentException mappingFailure) {
                return fail(sourceId, runId, trigger, "POSTING_MAPPING_FAILED", counters, wallStarted);
            }

            DiscoverySyncTransactions.PageResult page;
            try {
                page = transactions.persistPage(sourceId, runId, mapped, observedAt);
            } catch (RuntimeException persistenceFailure) {
                if (persistenceFailure instanceof JobSourceSyncLeaseLostException lost) throw lost;
                return fail(sourceId, runId, trigger, "PERSISTENCE_FAILURE", counters, wallStarted);
            }

            seenIds.addAll(pageIds);
            counters.discovered += mapped.size();
            counters.created += page.created();
            counters.updated += page.updated();
            counters.unchanged += page.unchanged();
            counters.reopened += page.reopened();

            for (DiscoverySyncTransactions.Observation observation : page.observations()) {
                boolean ready = observation.rankingReady();
                if (observation.extractionNeeded()) {
                    try {
                        ready = transactions.extract(sourceId, runId, observation.listingId(),
                                observation.fingerprint());
                    } catch (RuntimeException extractionFailure) {
                        if (extractionFailure instanceof JobSourceSyncLeaseLostException lost) throw lost;
                        ready = false;
                        log.warn("Lever extraction failed sourceId={} syncRunId={} listingId={}",
                                sourceId, runId, observation.listingId());
                    }
                }
                if (ready) counters.rankingReady++; else counters.unready++;
            }

            log.debug("Lever sync page sourceId={} syncRunId={} pageIndex={} skip={} fetched={} discovered={}",
                    sourceId, runId, pageIndex, skip, postings.size(), counters.discovered);

            if (postings.size() < PAGE_LIMIT) {
                try {
                    JobSourceSyncResult result = transactions.succeed(sourceId, runId, Set.copyOf(seenIds),
                            observedAt, counters);
                    terminalLog(sourceId, trigger, result, wallStarted);
                    return result;
                } catch (RuntimeException finalizationFailure) {
                    if (finalizationFailure instanceof JobSourceSyncLeaseLostException lost) throw lost;
                    return fail(sourceId, runId, trigger, "PERSISTENCE_FAILURE", counters, wallStarted);
                }
            }
            if (pageIndex == MAX_PAGES - 1) {
                return fail(sourceId, runId, trigger, "TRAVERSAL_LIMIT_EXCEEDED", counters, wallStarted);
            }
            skip += postings.size();
        }
        throw new IllegalStateException("unreachable traversal state");
    }

    private JobSourceSyncResult fail(long sourceId, long runId, JobSourceSyncTrigger trigger, String failureCode,
            MutableCounters counters, long wallStarted) {
        JobSourceSyncResult result = transactions.fail(sourceId, runId, failureCode, counters);
        terminalLog(sourceId, trigger, result, wallStarted);
        return result;
    }

    private void terminalLog(long sourceId, JobSourceSyncTrigger trigger, JobSourceSyncResult result,
            long wallStarted) {
        JobSourceSyncResult.Counters counters = result.counters();
        log.info("Lever sync completed sourceId={} syncRunId={} trigger={} status={} failureCode={} discovered={} "
                        + "created={} updated={} unchanged={} closed={} reopened={} rankingReady={} unready={} durationMs={}",
                sourceId, result.runId(), trigger, result.status(), result.failureCode(), counters.discovered(),
                counters.created(), counters.updated(), counters.unchanged(), counters.closed(), counters.reopened(),
                counters.rankingReady(), counters.unready(),
                Duration.ofNanos(Math.max(0, System.nanoTime() - wallStarted)).toMillis());
    }

    private LocalDateTime now() {
        return DiscoveryTimestamps.toDatabasePrecision(LocalDateTime.now(clock), "now");
    }

    private static boolean hasConstraint(Throwable failure, String expected) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof ConstraintViolationException constraint
                    && expected.equalsIgnoreCase(constraint.getConstraintName())) return true;
        }
        return false;
    }
}
