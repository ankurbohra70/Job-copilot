package com.jobcopilot.intelligence.evaluation;

import com.jobcopilot.intelligence.JobIntelligenceModel;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static com.jobcopilot.intelligence.evaluation.EvaluationResult.*;
import static org.assertj.core.api.Assertions.assertThat;

class EvaluationCoordinatorTest {
    private static final String EMPTY = """
            {"facts":{"skills":[],"experienceClauses":[],"qualifications":[]},
             "interpretations":{"roleFamily":null,"seniority":null,"responsibilities":[],"technicalConcepts":[]},
             "evidence":[],"uncertainties":[{"code":"INSUFFICIENT_CONTEXT","target":"facts","evidenceIds":[]}]}
            """;

    @Test
    void lanesRemainIndependentWhenBaselineOrAnotherLaneFails() {
        LoadedEvaluation loaded = new EvaluationDatasetLoader().load();
        EvaluationCase sparse = loaded.dataset().cases().stream()
                .filter(value -> value.id().equals("24-sparse-unsupported")).findFirst().orElseThrow();
        EvaluatedCase evaluated = new SemanticComparator(EvaluationDatasetLoader.DATASET_VERSION,
                EvaluationDatasetLoader.RESPONSE_VERSION).evaluate(
                EvaluationRunnerTest.coordinator(new FixtureBackedModelSource(loaded.responses())).execute(sparse));

        assertThat(evaluated.baseline().terminalStatus()).isEqualTo(TerminalStatus.BASELINE_UNAVAILABLE);
        assertThat(evaluated.hybrid().terminalStatus()).isEqualTo(TerminalStatus.VALIDATION_FAILURE);
        assertThat(evaluated.llmFirst().terminalStatus()).isEqualTo(TerminalStatus.SUCCEEDED);
    }

    @Test
    void unexpectedRuntimeDefectsAreLaneLocalAndMessagesAreDiscarded() {
        EvaluationCase value = new EvaluationDatasetLoader().load().dataset().cases().getFirst();
        EvaluationCaseExecution execution = EvaluationRunnerTest.coordinator((caseId, strategy) -> {
            if (strategy == Strategy.HYBRID_ENRICHMENT) throw new IllegalArgumentException("secret-message");
            return completed(EMPTY, null);
        }).execute(value);

        assertThat(execution.hybrid()).isEqualTo(new DefectExecution("IllegalArgumentException"));
        assertThat(execution.llmFirst()).isInstanceOf(IntelligenceExecution.class);
        assertThat(execution.toString()).doesNotContain("secret-message");
    }

    @Test
    void promptInputsContainOnlyTheirAuthorizedFields() {
        EvaluationCase value = new EvaluationDatasetLoader().load().dataset().cases().getFirst();
        Map<Strategy, String> strategyData = new EnumMap<>(Strategy.class);
        EvaluationCaseExecution execution = EvaluationRunnerTest.coordinator((caseId, strategy) ->
                completed(EMPTY, input -> strategyData.put(strategy, input.strategyData()))).execute(value);

        assertThat(((IntelligenceExecution) execution.hybrid()).invocationCount()).isEqualTo(1);
        assertThat(((IntelligenceExecution) execution.llmFirst()).invocationCount()).isEqualTo(1);
        assertThat(strategyData.get(Strategy.HYBRID_ENRICHMENT))
                .contains("\"canonicalRequirements\"").contains("\"requiredSkills\":[\"Java\"]");
        assertThat(strategyData.get(Strategy.LLM_FIRST)).contains("\"title\"", "\"description\"")
                .doesNotContain("canonicalRequirements", value.id(), "canonical-accurate", "JC005", "fixture");
        assertThat(strategyData.values()).allMatch(data -> !data.contains("reviewStatus") && !data.contains("gold"));
    }

    private static JobIntelligenceModel completed(String candidate,
            java.util.function.Consumer<JobIntelligenceModel.ModelInput> observer) {
        return new JobIntelligenceModel() {
            public String providerId() { return "fixture"; }
            public ModelAttemptResult analyze(ModelInput input) {
                if (observer != null) observer.accept(input);
                return new ModelAttemptResult(candidate, Outcome.COMPLETED, null, "jc007-fixture-v1",
                        null, new Usage(0L, 0L, 0L), Duration.ZERO);
            }
        };
    }
}
