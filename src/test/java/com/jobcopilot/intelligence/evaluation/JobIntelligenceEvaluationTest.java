package com.jobcopilot.intelligence.evaluation;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static com.jobcopilot.intelligence.evaluation.EvaluationResult.*;
import static org.assertj.core.api.Assertions.assertThat;

class JobIntelligenceEvaluationTest {
    @Test
    void evaluatesAllReviewedCasesThroughTheRealPipelines() throws Exception {
        LoadedEvaluation loaded = new EvaluationDatasetLoader().load();
        FixtureBackedModelSource fixtures = new FixtureBackedModelSource(loaded.responses());
        EvaluationCoordinator coordinator = EvaluationRunnerTest.coordinator(fixtures);
        SemanticComparator comparator = new SemanticComparator(
                loaded.dataset().datasetVersion(), loaded.responses().scriptedResponseVersion());
        List<EvaluatedCase> evaluated = new ArrayList<>();
        for (EvaluationCase evaluationCase : loaded.dataset().cases()) {
            EvaluationCaseExecution execution = coordinator.execute(evaluationCase);
            assertThat(((IntelligenceExecution) execution.hybrid()).invocationCount()).isEqualTo(1);
            assertThat(((IntelligenceExecution) execution.llmFirst()).invocationCount()).isEqualTo(1);
            assertThat(fixtures.invocationCount(evaluationCase.id(), Strategy.HYBRID_ENRICHMENT)).isEqualTo(1);
            assertThat(fixtures.invocationCount(evaluationCase.id(), Strategy.LLM_FIRST)).isEqualTo(1);
            evaluated.add(comparator.evaluate(execution));
        }

        assertThat(loaded.dataset().datasetVersion()).isEqualTo("jc007-evaluation-v1");
        assertThat(evaluated).hasSize(24);
        assertThat(evaluated).flatExtracting(EvaluatedCase::strategies)
                .noneMatch(value -> value.terminalStatus() == TerminalStatus.EXECUTION_DEFECT);

        EvaluationReport reference = EvaluationRunnerTest.evaluateFixtureBacked();
        EvaluationReport report = new EvaluationReport(reference.reportMetadata(), reference.fixtureDisclosure(),
                new MetricsAggregator().aggregate(evaluated), List.copyOf(evaluated));
        RenderedReport rendered = new EvaluationReportRenderer().render(report);
        Path output = Path.of("target", "job-intelligence-evaluation");
        Files.createDirectories(output);
        Files.writeString(output.resolve("report.txt"), rendered.text(), StandardCharsets.UTF_8);
        Files.writeString(output.resolve("report.json"), rendered.json(), StandardCharsets.UTF_8);
        assertThat(rendered.text()).startsWith("JC-007 Phase 5 Evaluation\nmode=FIXTURE_BACKED");
    }
}
