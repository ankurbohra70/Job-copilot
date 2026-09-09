package com.jobcopilot.intelligence;

import java.time.Duration;
import java.util.List;

/** One provider attempt, with no retry, database, or validation responsibility. */
public interface JobIntelligenceModel {
    String providerId();
    ModelAttemptResult analyze(ModelInput input);

    /** Read-only request; only prompt assembly can create the permitted implementation. */
    sealed interface ModelInput permits JobIntelligencePrompt.AssembledInput {
        String model();
        String instructions();
        String strategyData();
        String promptVersion();
        String schemaVersion();
        String outputSchema();
        GenerationSettings generationSettings();
        Duration remainingTimeout();
    }
    /** Null temperature means omit it for models that do not support that control. */
    record GenerationSettings(Double temperature, int maxOutputTokens) {}
    /** Candidate output is untrusted, even when the provider reports COMPLETED. */
    record Output(JobIntelligence.Facts facts, JobIntelligence.Interpretations interpretations,
            List<JobIntelligence.Evidence> evidence, List<JobIntelligence.Uncertainty> uncertainties) {
        public Output { evidence = List.copyOf(evidence); uncertainties = List.copyOf(uncertainties); }
    }
    enum Outcome { COMPLETED, REFUSED, INCOMPLETE, RETRYABLE_FAILURE, PERMANENT_FAILURE }
    record Usage(Long inputTokens, Long outputTokens, Long cachedInputTokens) {}
    record ModelAttemptResult(String candidateJson, Outcome outcome, String failureCode,
            String returnedModel, String providerRequestId, Usage usage, Duration latency) {}
}
