package com.jobcopilot.intelligence.evaluation;

import com.jobcopilot.intelligence.JobIntelligenceModel;
import com.jobcopilot.intelligence.JobIntelligencePrompt;
import com.jobcopilot.job.JobExtractionBaseline;
import com.jobcopilot.job.JobRequirementExtractor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import static com.jobcopilot.intelligence.evaluation.EvaluationResult.*;
import static org.assertj.core.api.Assertions.assertThat;

class EvaluationRunnerTest {
    @Test
    void evaluatesEveryStrategyAndWritesDeterministicReports() throws Exception {
        EvaluationReport report = evaluateFixtureBacked();
        RenderedReport first = new EvaluationReportRenderer().render(report);
        RenderedReport second = new EvaluationReportRenderer().render(evaluateFixtureBacked());

        assertThat(report.cases()).hasSize(24);
        assertThat(first).isEqualTo(second);
        assertThat(first.text()).contains("fixture-backed results validate harness mechanics")
                .contains("HYBRID_ENRICHMENT").contains("LLM_FIRST").contains("JC005");
        assertThat(first.json()).contains("\"mode\" : \"FIXTURE_BACKED\"");

        Path output = Path.of("target", "job-intelligence-evaluation");
        Files.createDirectories(output);
        Files.writeString(output.resolve("report.txt"), first.text(), StandardCharsets.UTF_8);
        Files.writeString(output.resolve("report.json"), first.json(), StandardCharsets.UTF_8);
    }

    static EvaluationReport evaluateFixtureBacked() {
        LoadedEvaluation loaded = new EvaluationDatasetLoader().load();
        FixtureBackedModelSource source = new FixtureBackedModelSource(loaded.responses());
        EvaluationCoordinator coordinator = coordinator(source);
        SemanticComparator comparator = new SemanticComparator(
                loaded.dataset().datasetVersion(), loaded.responses().scriptedResponseVersion());
        List<EvaluatedCase> cases = loaded.dataset().cases().stream()
                .map(coordinator::execute).map(comparator::evaluate).toList();
        EvaluationSummary summary = new MetricsAggregator().aggregate(cases);
        ReproducibilityMetadata metadata = new ReproducibilityMetadata(
                SemanticComparator.MODE, loaded.dataset().datasetVersion(),
                loaded.responses().scriptedResponseVersion(), loaded.dataset().matchingVocabularyVersion(),
                null, "fixture", "fixture-evaluation", "jc007-fixture-v1", null, null, null);
        int reusedHybridCandidates = (int) loaded.responses().cases().stream()
                .filter(value -> "HYBRID".equals(value.llmFirst().candidateRef())).count();
        return new EvaluationReport(metadata,
                new FixtureDisclosure(loaded.responses().cases().size(), reusedHybridCandidates), summary, cases);
    }

    static EvaluationCoordinator coordinator(EvaluationCoordinator.ModelSource source) {
        return new EvaluationCoordinator(new JobExtractionBaseline(new JobRequirementExtractor()),
                new JobIntelligencePrompt(), source,
                new JobIntelligencePrompt.ExecutionSettings("fixture-evaluation",
                        new JobIntelligenceModel.GenerationSettings(0.0, 4096), Duration.ofSeconds(5)));
    }
}
