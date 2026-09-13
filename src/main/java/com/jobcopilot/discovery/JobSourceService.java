package com.jobcopilot.discovery;

import com.jobcopilot.discovery.JobSourceApiExceptions.DuplicateSource;
import com.jobcopilot.discovery.JobSourceApiExceptions.InvalidQuery;
import com.jobcopilot.discovery.JobSourceApiExceptions.InvalidSource;
import com.jobcopilot.discovery.JobSourceApiExceptions.InvalidStoredSource;
import com.jobcopilot.discovery.JobSourceApiExceptions.ListingNotFound;
import com.jobcopilot.discovery.JobSourceApiExceptions.PersistenceFailure;
import com.jobcopilot.discovery.JobSourceApiExceptions.RunNotFound;
import com.jobcopilot.discovery.JobSourceApiExceptions.SourceDisabled;
import com.jobcopilot.discovery.JobSourceApiExceptions.SourceNotFound;
import com.jobcopilot.discovery.JobSourceApiExceptions.SynchronizationFailure;
import com.jobcopilot.discovery.JobSourceApiExceptions.UnsupportedProvider;
import com.jobcopilot.discovery.dto.CreateJobSourceRequest;
import com.jobcopilot.discovery.dto.ExternalJobListingPageResponse;
import com.jobcopilot.discovery.dto.ExternalJobListingResponse;
import com.jobcopilot.discovery.dto.JobSourcePageResponse;
import com.jobcopilot.discovery.dto.JobSourceResponse;
import com.jobcopilot.discovery.dto.JobSourceSyncRunPageResponse;
import com.jobcopilot.discovery.dto.JobSourceSyncRunResponse;
import com.jobcopilot.discovery.lever.LeverSource;
import com.jobcopilot.job.DiscoveryJobReadService;
import com.jobcopilot.job.JobRequirementExtractor;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.Locale;
import java.util.Set;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class JobSourceService {
    private static final int MAX_PAGE_SIZE = 100;
    private static final Set<String> SOURCE_SORTS = Set.of(
            "id", "companyName", "enabled", "createdAt", "updatedAt", "lastSuccessfulSyncAt");
    private static final Set<String> RUN_SORTS = Set.of("id", "startedAt", "completedAt", "status");
    private static final Set<String> LISTING_SORTS = Set.of(
            "id", "availability", "firstSeenAt", "lastSeenAt", "lastVerifiedAt");
    private static final Set<String> PUBLIC_FAILURE_CODES = Set.of(
            "SOURCE_NOT_FOUND", "RATE_LIMITED", "REQUEST_REJECTED", "PROVIDER_UNAVAILABLE",
            "TIMEOUT", "CONNECTION_FAILURE", "INTERRUPTED", "MALFORMED_RESPONSE", "INVALID_RESPONSE",
            "RESPONSE_TOO_LARGE", "POSTING_MAPPING_FAILED", "DUPLICATE_EXTERNAL_ID_DURING_TRAVERSAL",
            "TRAVERSAL_LIMIT_EXCEEDED", "PERSISTENCE_FAILURE", "APPLICATION_RESTARTED", "LEASE_EXPIRED");

    private final JobSourceRepository sources;
    private final JobSourceSyncRunRepository runs;
    private final ExternalJobListingRepository listings;
    private final LeverJobSourceSynchronizer synchronizer;
    private final ExtractionFingerprint fingerprints;
    private final JobRequirementExtractor extractor;
    private final DiscoveryJobReadService jobReads;

    public JobSourceService(JobSourceRepository sources, JobSourceSyncRunRepository runs,
            ExternalJobListingRepository listings, LeverJobSourceSynchronizer synchronizer,
            ExtractionFingerprint fingerprints, JobRequirementExtractor extractor,
            DiscoveryJobReadService jobReads) {
        this.sources = sources;
        this.runs = runs;
        this.listings = listings;
        this.synchronizer = synchronizer;
        this.fingerprints = fingerprints;
        this.extractor = extractor;
        this.jobReads = jobReads;
    }

    @Transactional
    public JobSourceResponse create(CreateJobSourceRequest request) {
        if (request.provider() != JobSourceProvider.LEVER) throw new UnsupportedProvider();
        JobSource source;
        try {
            source = new JobSource(request.provider(), request.region(), request.sourceKey(),
                    request.companyName(), request.enabled());
            new LeverSource(source.region(), source.sourceKey());
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new InvalidSource("Invalid Lever job source identity");
        }
        try {
            return toResponse(sources.saveAndFlush(source));
        } catch (DataIntegrityViolationException failure) {
            if (hasConstraint(failure, "uq_job_sources_identity")) throw new DuplicateSource();
            throw new PersistenceFailure();
        }
    }

    @Transactional(readOnly = true)
    public JobSourceResponse get(long sourceId) {
        return toResponse(source(sourceId));
    }

    @Transactional(readOnly = true)
    public JobSourcePageResponse list(int page, int size, String sort, Boolean enabled) {
        PageRequest request = pageRequest(page, size, sort, "createdAt,desc", SOURCE_SORTS);
        Page<JobSource> result = enabled == null ? sources.findAll(request)
                : sources.findAllByEnabled(enabled, request);
        return new JobSourcePageResponse(result.map(JobSourceService::toResponse).getContent(),
                result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages(),
                result.isFirst(), result.isLast());
    }

    @Transactional
    public JobSourceResponse setEnabled(long sourceId, boolean enabled) {
        JobSource source = source(sourceId);
        source.changeEnabled(enabled);
        return toResponse(sources.saveAndFlush(source));
    }

    /** Intentionally non-transactional: Phase 3 owns all synchronization transactions. */
    public JobSourceSyncRunResponse synchronize(long sourceId) {
        requirePositive(sourceId, "sourceId");
        JobSource source = sources.findById(sourceId).orElseThrow(() -> new SourceNotFound(sourceId));
        if (!source.enabled()) throw new SourceDisabled(sourceId);
        if (source.provider() != JobSourceProvider.LEVER) throw new InvalidStoredSource(sourceId);
        try {
            new LeverSource(source.region(), source.sourceKey());
        } catch (RuntimeException invalid) {
            throw new InvalidStoredSource(sourceId);
        }

        JobSourceSyncResult result;
        try {
            result = synchronizer.synchronize(sourceId, JobSourceSyncTrigger.MANUAL);
        } catch (JobSourceSyncAlreadyRunningException conflict) {
            throw conflict;
        } catch (RuntimeException unexpected) {
            throw new SynchronizationFailure();
        }
        JobSourceSyncRun run = runs.findByIdAndJobSourceId(result.runId(), sourceId)
                .orElseThrow(SynchronizationFailure::new);
        return toResponse(run, sourceId);
    }

    @Transactional(readOnly = true)
    public JobSourceSyncRunPageResponse syncRuns(long sourceId, int page, int size, String sort,
            JobSourceSyncStatus status) {
        source(sourceId);
        PageRequest request = pageRequest(page, size, sort, "startedAt,desc", RUN_SORTS);
        Page<JobSourceSyncRun> result = status == null
                ? runs.findAllByJobSourceId(sourceId, request)
                : runs.findAllByJobSourceIdAndStatus(sourceId, status, request);
        return new JobSourceSyncRunPageResponse(
                result.map(run -> toResponse(run, sourceId)).getContent(), result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages(), result.isFirst(), result.isLast());
    }

    @Transactional(readOnly = true)
    public JobSourceSyncRunResponse syncRun(long sourceId, long runId) {
        requirePositive(sourceId, "sourceId");
        requirePositive(runId, "runId");
        if (!sources.existsById(sourceId)) throw new SourceNotFound(sourceId);
        return runs.findByIdAndJobSourceId(runId, sourceId).map(run -> toResponse(run, sourceId))
                .orElseThrow(() -> new RunNotFound(sourceId, runId));
    }

    @Transactional(readOnly = true)
    public ExternalJobListingPageResponse listings(long sourceId, int page, int size, String sort,
            ListingAvailability availability, Long jobId) {
        source(sourceId);
        if (jobId != null) requirePositive(jobId, "jobId");
        PageRequest request = pageRequest(page, size, sort, "lastVerifiedAt,desc", LISTING_SORTS);
        Page<ExternalJobListingReadProjection> result = listings.findPage(
                sourceId, availability, jobId, request);
        Set<Long> withSkills = skillReadyIds(result.getContent());
        return new ExternalJobListingPageResponse(
                result.getContent().stream().map(row -> toResponse(row, withSkills)).toList(),
                result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages(),
                result.isFirst(), result.isLast());
    }

    @Transactional(readOnly = true)
    public ExternalJobListingResponse listing(long sourceId, long listingId) {
        requirePositive(sourceId, "sourceId");
        requirePositive(listingId, "listingId");
        if (!sources.existsById(sourceId)) throw new SourceNotFound(sourceId);
        ExternalJobListingReadProjection row = listings.findReadProjection(sourceId, listingId)
                .orElseThrow(() -> new ListingNotFound(sourceId, listingId));
        return toResponse(row, skillReadyIds(Set.of(row)));
    }

    private Set<Long> skillReadyIds(Collection<ExternalJobListingReadProjection> rows) {
        return jobReads.idsWithSkills(rows.stream().map(ExternalJobListingReadProjection::jobId).toList());
    }

    private ExternalJobListingResponse toResponse(ExternalJobListingReadProjection row, Set<Long> withSkills) {
        ExtractionState extractionState = extractionState(row);
        boolean requirementsReady = withSkills.contains(row.jobId()) || positive(row.minYearsExperience());
        return new ExternalJobListingResponse(row.listingId(), row.sourceId(), row.externalJobId(),
                row.availability(), row.jobId(), row.jobStatus(), row.hostedJobUrl(), row.applyUrl(),
                extractionState, extractionState == ExtractionState.CURRENT && requirementsReady,
                row.firstSeenAt(), row.lastSeenAt(), row.lastVerifiedAt());
    }

    private ExtractionState extractionState(ExternalJobListingReadProjection row) {
        if (row.description() == null || row.description().isBlank()) return ExtractionState.UNAVAILABLE;
        if (row.extractionFingerprint() == null) return ExtractionState.PENDING;
        String current = fingerprints.calculate(extractor.version(), row.description());
        return current.equals(row.extractionFingerprint()) ? ExtractionState.CURRENT : ExtractionState.STALE;
    }

    private JobSource source(long sourceId) {
        requirePositive(sourceId, "sourceId");
        return sources.findById(sourceId).orElseThrow(() -> new SourceNotFound(sourceId));
    }

    private static boolean positive(BigDecimal value) { return value != null && value.signum() > 0; }

    private static JobSourceResponse toResponse(JobSource source) {
        return new JobSourceResponse(source.id(), source.provider(), source.region(), source.sourceKey(),
                source.companyName(), source.enabled(), source.lastSuccessfulSyncAt(), source.createdAt(),
                source.updatedAt());
    }

    private static JobSourceSyncRunResponse toResponse(JobSourceSyncRun run, long sourceId) {
        JobSourceSyncCounters counters = run.counters();
        return new JobSourceSyncRunResponse(run.id(), sourceId, run.trigger(), run.status(), run.startedAt(),
                run.completedAt(), safeFailureCode(run.failureCode()), counters.discovered(), counters.created(),
                counters.updated(), counters.unchanged(), counters.closed(), counters.reopened(),
                counters.rankingReady(), counters.unready());
    }

    static String safeFailureCode(String failureCode) {
        if (failureCode == null) return null;
        return PUBLIC_FAILURE_CODES.contains(failureCode) ? failureCode : "SYNC_FAILED";
    }

    private static PageRequest pageRequest(int page, int size, String expression, String defaultSort,
            Set<String> allowed) {
        if (page < 0) throw new InvalidQuery("page must be at least 0");
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new InvalidQuery("size must be between 1 and " + MAX_PAGE_SIZE);
        }
        String[] parts = (expression == null ? defaultSort : expression).split(",", -1);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new InvalidQuery("sort must use the format field,direction");
        }
        String field = parts[0].trim();
        String direction = parts[1].trim().toLowerCase(Locale.ROOT);
        if (!allowed.contains(field)) throw new InvalidQuery("unsupported sort field: " + field);
        if (!direction.equals("asc") && !direction.equals("desc")) {
            throw new InvalidQuery("sort direction must be asc or desc");
        }
        Sort.Direction resolved = Sort.Direction.fromString(direction);
        Sort sort = Sort.by(resolved, field);
        if (!field.equals("id")) sort = sort.and(Sort.by(resolved, "id"));
        return PageRequest.of(page, size, sort);
    }

    private static void requirePositive(long id, String name) {
        if (id <= 0) throw new InvalidQuery(name + " must be positive");
    }

    private static boolean hasConstraint(Throwable failure, String expected) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof ConstraintViolationException constraint
                    && expected.equalsIgnoreCase(constraint.getConstraintName())) return true;
        }
        return false;
    }
}
