package com.jobcopilot.discovery.workday;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

public sealed interface WorkdayFetchResult permits WorkdayFetchResult.SearchSuccess,
        WorkdayFetchResult.DetailSuccess, WorkdayFetchResult.Failure {
    record SearchSuccess(WorkdaySource source, WorkdayPageRequest page, Integer reportedTotal,
            List<WorkdaySearchPosting> postings, int returnedRowCount, Duration latency) implements WorkdayFetchResult {
        public SearchSuccess {
            Objects.requireNonNull(source); Objects.requireNonNull(page); requireLatency(latency);
            if (reportedTotal != null && reportedTotal < 0)
                throw new IllegalArgumentException("reportedTotal must not be negative");
            postings = List.copyOf(Objects.requireNonNull(postings));
            if (returnedRowCount < postings.size() || returnedRowCount > page.limit())
                throw new IllegalArgumentException("returned row count is invalid");
        }
        public SearchSuccess(WorkdaySource source, WorkdayPageRequest page, Integer reportedTotal,
                List<WorkdaySearchPosting> postings, Duration latency) {
            this(source, page, reportedTotal, postings, postings.size(), latency);
        }
    }
    record DetailSuccess(WorkdaySource source, WorkdayDetailRequest request,
            WorkdayJobDetail detail, Duration latency) implements WorkdayFetchResult {
        public DetailSuccess {
            Objects.requireNonNull(source); Objects.requireNonNull(request); Objects.requireNonNull(detail);
            requireLatency(latency);
        }
    }
    record Failure(WorkdaySource source, Operation operation, WorkdayPageRequest page,
            WorkdayDetailRequest detail, FailureKind kind, Integer httpStatus,
            Duration latency) implements WorkdayFetchResult {
        public Failure {
            Objects.requireNonNull(source); Objects.requireNonNull(operation); Objects.requireNonNull(kind);
            if ((operation == Operation.SEARCH) == (page == null)
                    || (operation == Operation.DETAIL) == (detail == null)) {
                throw new IllegalArgumentException("operation request context is invalid");
            }
            if (httpStatus != null && (httpStatus < 100 || httpStatus > 599))
                throw new IllegalArgumentException("httpStatus is invalid");
            requireLatency(latency);
        }
    }
    enum Operation { SEARCH, DETAIL }
    enum FailureKind { SOURCE_NOT_FOUND, RATE_LIMITED, REQUEST_REJECTED, PROVIDER_UNAVAILABLE,
        TIMEOUT, CONNECTION_FAILURE, INTERRUPTED, MALFORMED_RESPONSE, INVALID_RESPONSE,
        RESPONSE_TOO_LARGE, UNEXPECTED_CONTENT_TYPE }
    private static void requireLatency(Duration latency) {
        Objects.requireNonNull(latency, "latency is required");
        if (latency.isNegative()) throw new IllegalArgumentException("latency must not be negative");
    }
}
