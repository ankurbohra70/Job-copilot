package com.jobcopilot.intelligence.evaluation;

import com.jobcopilot.intelligence.JobIntelligenceModel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import static com.jobcopilot.intelligence.evaluation.EvaluationResult.*;
import static org.assertj.core.api.Assertions.assertThat;

class FailureTaxonomyAndIsolationTest {
    @Test
    void classifiesProviderDecodeValidationAndHarnessFailuresSeparately() {
        LoadedEvaluation loaded = new EvaluationDatasetLoader().load();
        EvaluationCase first = loaded.dataset().cases().getFirst();
        ConfiguredAttempt refused = new ConfiguredAttempt(JobIntelligenceModel.Outcome.REFUSED,
                "fixture-refusal", null, null, null);
        ConfiguredAttempt malformed = new ConfiguredAttempt(JobIntelligenceModel.Outcome.COMPLETED,
                null, null, "{", null);
        ScriptedResponseSet failures = new ScriptedResponseSet(EvaluationDatasetLoader.RESPONSE_VERSION,
                List.of(new ScriptedCaseResponse(first.id(), refused, malformed)));
        EvaluationCaseExecution raw = EvaluationRunnerTest.coordinator(new FixtureBackedModelSource(failures)).execute(first);
        EvaluatedCase compared = new SemanticComparator(EvaluationDatasetLoader.DATASET_VERSION,
                EvaluationDatasetLoader.RESPONSE_VERSION).evaluate(raw);

        assertThat(compared.hybrid().terminalStatus()).isEqualTo(TerminalStatus.PROVIDER_FAILURE);
        assertThat(compared.llmFirst().terminalStatus()).isEqualTo(TerminalStatus.DECODE_FAILURE);

        EvaluationCase sparse = loaded.dataset().cases().stream()
                .filter(value -> value.id().equals("24-sparse-unsupported")).findFirst().orElseThrow();
        EvaluatedCase validation = new SemanticComparator(EvaluationDatasetLoader.DATASET_VERSION,
                EvaluationDatasetLoader.RESPONSE_VERSION).evaluate(
                EvaluationRunnerTest.coordinator(new FixtureBackedModelSource(loaded.responses())).execute(sparse));
        assertThat(validation.hybrid().terminalStatus()).isEqualTo(TerminalStatus.VALIDATION_FAILURE);

        EvaluatedCase defect = new SemanticComparator(EvaluationDatasetLoader.DATASET_VERSION,
                EvaluationDatasetLoader.RESPONSE_VERSION).evaluate(
                EvaluationRunnerTest.coordinator((caseId, strategy) -> { throw new IllegalStateException("fixture"); })
                        .execute(first));
        assertThat(defect.hybrid().terminalStatus()).isEqualTo(TerminalStatus.EXECUTION_DEFECT);
    }

    @Test
    void evaluationPackageIsTestOnlyAndContainsNoLiveProviderClient() throws Exception {
        Path evaluation = Path.of("src", "test", "java", "com", "jobcopilot", "intelligence", "evaluation");
        assertThat(evaluation).exists();
        assertThat(Path.of("src", "main", "java", "com", "jobcopilot", "intelligence", "evaluation"))
                .doesNotExist();
        String source = Files.walk(evaluation).filter(path -> path.toString().endsWith(".java"))
                .filter(path -> !path.getFileName().toString().endsWith("Test.java"))
                .map(path -> {
                    try { return Files.readString(path); }
                    catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
                }).reduce("", String::concat);
        assertThat(source).doesNotContain("OpenAiJobIntelligenceModel", "WebClient", "RestClient", "HttpClient");

        LoadedEvaluation loaded = new EvaluationDatasetLoader().load();
        FixtureBackedModelSource fixtures = new FixtureBackedModelSource(loaded.responses());
        EvaluationCaseExecution execution = EvaluationRunnerTest.coordinator(fixtures)
                .execute(loaded.dataset().cases().getFirst());
        assertThat(((IntelligenceExecution) execution.hybrid()).invocationCount()).isEqualTo(1);
        assertThat(((IntelligenceExecution) execution.llmFirst()).invocationCount()).isEqualTo(1);
        assertThat(fixtures.invocationCount(loaded.dataset().cases().getFirst().id(), Strategy.HYBRID_ENRICHMENT))
                .isEqualTo(1);
    }
}
