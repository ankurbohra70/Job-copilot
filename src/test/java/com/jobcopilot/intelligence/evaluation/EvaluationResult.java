package com.jobcopilot.intelligence.evaluation;

import com.jobcopilot.intelligence.JobIntelligence;
import com.jobcopilot.intelligence.JobIntelligenceResult;
import com.jobcopilot.job.JobExtractionBaseline;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

final class EvaluationResult {
    private EvaluationResult() {}

    enum Strategy { JC005, HYBRID_ENRICHMENT, LLM_FIRST }
    enum TerminalStatus {
        SUCCEEDED,
        BASELINE_UNAVAILABLE,
        PREFLIGHT_FAILURE,
        PROVIDER_FAILURE,
        EXECUTION_FAILURE,
        DECODE_FAILURE,
        VALIDATION_FAILURE,
        EXECUTION_DEFECT
    }

    sealed interface RawExecution permits BaselineExecution, IntelligenceExecution, DefectExecution {}
    record BaselineExecution(JobExtractionBaseline.Result result) implements RawExecution {}
    record IntelligenceExecution(JobIntelligenceResult result, int invocationCount) implements RawExecution {}
    record DefectExecution(String exceptionType) implements RawExecution {}

    record EvaluationCaseExecution(
            EvaluationCase evaluationCase,
            RawExecution baseline,
            RawExecution hybrid,
            RawExecution llmFirst) {}

    record FailureDescriptor(String stage, String code, String location, String exceptionType) {}
    record Counts(int truePositive, int falsePositive, int falseNegative) {
        Counts plus(Counts other) {
            return new Counts(truePositive + other.truePositive, falsePositive + other.falsePositive,
                    falseNegative + other.falseNegative);
        }
    }
    record ClaimBreakdown(
            Map<JobIntelligence.Importance, Counts> byImportance,
            int requiredToPreferred,
            int preferredToRequired,
            int duplicatePredictions,
            int crossClassConflicts,
            int evidenceAnchorMismatches,
            List<String> falsePositives,
            List<String> falseNegatives) {
        ClaimBreakdown {
            byImportance = Map.copyOf(byImportance);
            falsePositives = List.copyOf(falsePositives);
            falseNegatives = List.copyOf(falseNegatives);
        }
    }
    record ExperienceBreakdown(
            boolean applicable,
            int goldClauses,
            int detected,
            int missed,
            int extra,
            int exact,
            int quantityMismatches,
            int importanceMismatches,
            int scopeMismatches,
            int conditionalityMismatches,
            int evidenceAnchorMismatches) {}
    record MinimumBreakdown(
            boolean applicable,
            boolean exact,
            boolean statusMismatch,
            boolean numericMismatch,
            boolean missingUsableResult) {}
    record SemanticBreakdown(
            ClaimBreakdown skills,
            ClaimBreakdown qualifications,
            ExperienceBreakdown experience,
            MinimumBreakdown minimumExperience) {}
    record ReproducibilityMetadata(
            String mode,
            String datasetVersion,
            String scriptedResponseVersion,
            String matchingVocabularyVersion,
            String strategy,
            String providerId,
            String requestedModel,
            String returnedModel,
            String promptIdentity,
            String schemaIdentity,
            String validationPolicyVersion) {}
    record StrategyEvaluation(
            String caseId,
            List<String> tags,
            Strategy strategy,
            TerminalStatus terminalStatus,
            FailureDescriptor failure,
            SemanticBreakdown semantic,
            boolean acceptedAbstention,
            boolean correctAbstention,
            boolean abstentionWithMissedGold,
            ReproducibilityMetadata metadata) {
        StrategyEvaluation { tags = List.copyOf(tags); }
    }
    record EvaluatedCase(
            String caseId,
            List<String> tags,
            StrategyEvaluation baseline,
            StrategyEvaluation hybrid,
            StrategyEvaluation llmFirst) {
        EvaluatedCase { tags = List.copyOf(tags); }
        List<StrategyEvaluation> strategies() { return List.of(baseline, hybrid, llmFirst); }
    }

    record Rates(BigDecimal precision, BigDecimal recall, BigDecimal f1) {}
    record ClaimSummary(Map<JobIntelligence.Importance, Counts> counts,
            Map<JobIntelligence.Importance, Rates> rates,
            int requiredToPreferred,
            int preferredToRequired,
            int duplicatePredictions,
            int crossClassConflicts,
            int evidenceAnchorMismatches) {
        ClaimSummary { counts = Map.copyOf(counts); rates = Map.copyOf(rates); }
    }
    record ExperienceSummary(boolean applicable, int goldClauses, int detected, int missed, int extra,
            int exact, int quantityMismatches, int importanceMismatches, int scopeMismatches,
            int conditionalityMismatches, int evidenceAnchorMismatches) {}
    record MinimumSummary(boolean applicable, int exact, int totalCases, BigDecimal exactAllRate,
            int successfulCases, BigDecimal exactSuccessfulRate, int statusMismatches,
            int numericMismatches, int missingUsableResults) {}
    record ReliabilitySummary(int succeeded, int acceptedAbstentions, int correctAbstentions,
            int abstentionsWithMissedGold, int baselineUnavailable, int preflightFailures,
            int providerFailures, int executionFailures, int decodeFailures, int validationFailures,
            int executionDefects) {}
    record StrategySummary(Strategy strategy, ClaimSummary skills, ClaimSummary qualifications,
            ExperienceSummary experience, MinimumSummary minimumExperience, ReliabilitySummary reliability) {}
    record TagStrategySummary(int cases, int succeeded, Counts requiredSkills, int minimumExact) {}
    record EvaluationSummary(Map<Strategy, StrategySummary> strategies,
            Map<String, Map<Strategy, TagStrategySummary>> tags) {
        EvaluationSummary {
            strategies = Map.copyOf(strategies);
            tags = Collections.unmodifiableMap(new TreeMap<>(tags));
        }
    }
    record FixtureDisclosure(int llmFirstCases, int reusedHybridCandidatePayloads) {}
    record EvaluationReport(ReproducibilityMetadata reportMetadata, FixtureDisclosure fixtureDisclosure,
            EvaluationSummary summary,
            List<EvaluatedCase> cases) {
        EvaluationReport { cases = List.copyOf(cases); }
    }
    record RenderedReport(String text, String json) {}
}
