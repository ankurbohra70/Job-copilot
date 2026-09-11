package com.jobcopilot.intelligence;

import java.time.Duration;

/** In-memory terminal result. Metadata is never mixed into accepted intelligence. */
public sealed interface JobIntelligenceResult {
    AttemptMetadata metadata();

    record Accepted(JobIntelligence intelligence, AttemptMetadata metadata) implements JobIntelligenceResult {
        public Accepted {
            if (intelligence == null || metadata == null) throw new IllegalArgumentException("accepted result is incomplete");
        }
    }

    record Failed(Failure failure, AttemptMetadata metadata) implements JobIntelligenceResult {
        public Failed {
            if (failure == null || metadata == null) throw new IllegalArgumentException("failed result is incomplete");
        }
    }

    enum Stage { PREFLIGHT, EXECUTION, DECODE, VALIDATION }

    /** Bounded failure. Code and location are closed/typed; never exception text or rejected excerpts. */
    record Failure(Stage stage, Code code, Location location) {
        public Failure {
            if (stage == null || code == null || location == null) throw new IllegalArgumentException("failure is incomplete");
        }
    }

    record Location(String path) {
        public Location {
            path = path == null ? "" : path;
            if (path.length() > 160 || !path.matches("(?:[a-zA-Z]+(?:\\[[0-9]{1,2}\\])?)(?:\\.[a-zA-Z]+(?:\\[[0-9]{1,2}\\])?)*|")) {
                path = "";
            }
        }
        static Location root() { return new Location(""); }
        static Location of(String path) { return new Location(path); }
    }

    enum Code {
        INVALID_REQUEST,
        INVALID_TIMEOUT,
        TIMEOUT,
        INTERRUPTED,
        INVOCATION_EXCEPTION,
        EXECUTION_UNAVAILABLE,
        PROVIDER_CONTRACT_VIOLATION,
        PROVIDER_REFUSED,
        PROVIDER_INCOMPLETE,
        PROVIDER_RETRYABLE_FAILURE,
        PROVIDER_PERMANENT_FAILURE,
        EMPTY_CANDIDATE,
        CANDIDATE_TOO_LARGE,
        NESTING_TOO_DEEP,
        NUMERIC_TOKEN_TOO_LONG,
        PROCESSING_VOLUME_EXCEEDED,
        NOT_SINGLE_OBJECT,
        TRAILING_CONTENT,
        MALFORMED_JSON,
        DUPLICATE_FIELD,
        UNKNOWN_FIELD,
        MISSING_FIELD,
        INVALID_ENUM,
        TYPE_MISMATCH,
        NULL_NOT_ALLOWED,
        NULL_COLLECTION_ELEMENT,
        BOUND_VIOLATION,
        DUPLICATE_EVIDENCE_ID,
        EVIDENCE_QUOTE_MISMATCH,
        DANGLING_EVIDENCE_ID,
        UNSUPPORTED_CLAIM_FORM,
        UNSUPPORTED_INTERPRETATION,
        FACT_NOT_GROUNDED,
        INCONSISTENT_EXPERIENCE,
        VALIDATOR_DEFECT
    }

    enum Strategy { LLM_FIRST, HYBRID_ENRICHMENT }

    /**
     * Bounded safe attempt metadata for later evaluation/persistence.
     * Omits exception text, response bodies, headers, provider failure text, and rejected excerpts.
     */
    record AttemptMetadata(
            Strategy strategy,
            boolean invocationStarted,
            String providerId,
            String requestedModel,
            String returnedModel,
            String providerRequestId,
            String promptIdentity,
            String schemaIdentity,
            JobIntelligenceModel.GenerationSettings generationSettings,
            Duration effectiveTimeout,
            Duration runnerElapsed,
            Duration providerLatency,
            JobIntelligenceModel.Usage tokenUsage,
            JobIntelligenceModel.Outcome providerOutcome,
            String validationPolicyVersion,
            String strategyDataDigest
    ) {
        public AttemptMetadata {
            providerId = safeIdentifier(providerId);
            requestedModel = safeIdentifier(requestedModel);
            returnedModel = safeIdentifier(returnedModel);
            providerRequestId = safeIdentifier(providerRequestId);
            promptIdentity = safeIdentifier(promptIdentity);
            schemaIdentity = safeIdentifier(schemaIdentity);
            validationPolicyVersion = safeIdentifier(validationPolicyVersion);
            strategyDataDigest = safeIdentifier(strategyDataDigest);
            providerLatency = providerLatency == null || providerLatency.isNegative() ? null : providerLatency;
            if (tokenUsage != null) tokenUsage = new JobIntelligenceModel.Usage(
                    nonnegative(tokenUsage.inputTokens()), nonnegative(tokenUsage.outputTokens()), nonnegative(tokenUsage.cachedInputTokens()));
        }
        private static Long nonnegative(Long value) { return value == null || value < 0 ? null : value; }
    }

    /** Omit rather than truncate external identifiers; never manufacture a different identity. */
    private static String safeIdentifier(String value) {
        return value != null && value.length() <= 256 && value.matches("[A-Za-z0-9][A-Za-z0-9._:/@+\\-]*") ? value : null;
    }
}
