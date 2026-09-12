package com.jobcopilot.discovery.lever;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

public sealed interface LeverFetchResult permits LeverFetchResult.Success, LeverFetchResult.Failure {
    record Success(
            LeverSource source,
            LeverPageRequest page,
            List<LeverPosting> postings,
            Duration latency) implements LeverFetchResult {
        public Success {
            Objects.requireNonNull(source);
            Objects.requireNonNull(page);
            postings = List.copyOf(Objects.requireNonNull(postings));
            requireLatency(latency);
        }
    }

    record Failure(
            LeverSource source,
            LeverPageRequest page,
            FailureKind kind,
            Integer httpStatus,
            Duration latency) implements LeverFetchResult {
        public Failure {
            Objects.requireNonNull(source);
            Objects.requireNonNull(page);
            Objects.requireNonNull(kind);
            if (httpStatus != null && (httpStatus < 100 || httpStatus > 599))
                throw new IllegalArgumentException("httpStatus is invalid");
            requireLatency(latency);
        }
    }

    enum FailureKind {
        SOURCE_NOT_FOUND,
        RATE_LIMITED,
        REQUEST_REJECTED,
        PROVIDER_UNAVAILABLE,
        TIMEOUT,
        CONNECTION_FAILURE,
        INTERRUPTED,
        MALFORMED_RESPONSE,
        INVALID_RESPONSE,
        RESPONSE_TOO_LARGE
    }

    private static void requireLatency(Duration latency) {
        Objects.requireNonNull(latency, "latency is required");
        if (latency.isNegative()) throw new IllegalArgumentException("latency must not be negative");
    }
}
