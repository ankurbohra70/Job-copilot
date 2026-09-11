package com.jobcopilot.intelligence.evaluation;

import com.jobcopilot.intelligence.JobIntelligence;
import com.jobcopilot.intelligence.JobIntelligenceModel;
import com.jobcopilot.intelligence.JobIntelligencePrompt;
import com.jobcopilot.intelligence.JobIntelligenceResult;
import com.jobcopilot.job.JobExtractionBaseline;
import com.jobcopilot.job.dto.JobRequirementsResponse;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import static com.jobcopilot.intelligence.evaluation.EvaluationResult.*;
import static org.assertj.core.api.Assertions.assertThat;

class SemanticComparatorTest {
    @Test
    void comparesNormalizedSkillsWithoutSubstringConfusion() {
        EvaluationReport report = EvaluationRunnerTest.evaluateFixtureBacked();
        EvaluatedCase aliases = caseById(report, "04-aliases");
        EvaluatedCase javaScript = caseById(report, "06-java-javascript");

        assertThat(aliases.hybrid().semantic().skills().byImportance().get(JobIntelligence.Importance.REQUIRED))
                .isEqualTo(new Counts(2, 0, 0));
        assertThat(javaScript.hybrid().semantic().skills().byImportance().get(JobIntelligence.Importance.REQUIRED))
                .isEqualTo(new Counts(2, 0, 0));
        assertThat(javaScript.hybrid().semantic().skills().falsePositives()).isEmpty();
    }

    @Test
    void distinguishesAggregateMinimumFromClauseSemantics() {
        EvaluationReport report = EvaluationRunnerTest.evaluateFixtureBacked();
        EvaluatedCase conditional = caseById(report, "15-conditional-experience");
        EvaluatedCase relevant = caseById(report, "16-relevant-experience");

        assertThat(conditional.hybrid().semantic().experience().exact()).isEqualTo(1);
        assertThat(conditional.hybrid().semantic().minimumExperience().exact()).isTrue();
        assertThat(relevant.hybrid().semantic().experience().exact()).isEqualTo(1);
        assertThat(relevant.hybrid().semantic().minimumExperience().exact()).isTrue();
    }

    @Test
    void accountsForFalseClaimsInversionsDuplicatesAndCrossClassConflicts() {
        EvaluationCase value = skillCase();
        JobIntelligence.Evidence java = new JobIntelligence.Evidence("e1", JobIntelligence.Source.DESCRIPTION,
                "Java is required.");
        JobIntelligence.Evidence docker = new JobIntelligence.Evidence("e2", JobIntelligence.Source.DESCRIPTION,
                "Docker is preferred.");
        List<JobIntelligence.Skill> inverted = List.of(
                new JobIntelligence.Skill("Java", JobIntelligence.Importance.PREFERRED, List.of("e1")),
                new JobIntelligence.Skill("Java", JobIntelligence.Importance.PREFERRED, List.of("e1")),
                new JobIntelligence.Skill("Docker", JobIntelligence.Importance.REQUIRED, List.of("e2")));
        StrategyEvaluation result = compare(value, intelligence(inverted, List.of(java, docker))).hybrid();

        assertThat(result.semantic().skills().byImportance().get(JobIntelligence.Importance.REQUIRED))
                .isEqualTo(new Counts(0, 1, 1));
        assertThat(result.semantic().skills().byImportance().get(JobIntelligence.Importance.PREFERRED))
                .isEqualTo(new Counts(0, 1, 1));
        assertThat(result.semantic().skills().requiredToPreferred()).isEqualTo(1);
        assertThat(result.semantic().skills().preferredToRequired()).isEqualTo(1);
        assertThat(result.semantic().skills().duplicatePredictions()).isEqualTo(1);
        assertThat(result.semantic().skills().crossClassConflicts()).isZero();

        List<JobIntelligence.Skill> conflict = List.of(
                new JobIntelligence.Skill("Java", JobIntelligence.Importance.REQUIRED, List.of("e1")),
                new JobIntelligence.Skill("Java", JobIntelligence.Importance.PREFERRED, List.of("e1")));
        assertThat(compare(value, intelligence(conflict, List.of(java))).hybrid().semantic().skills()
                .crossClassConflicts()).isEqualTo(1);
    }

    private static EvaluationCase skillCase() {
        String description = "Java is required. Docker is preferred.";
        return new EvaluationCase("synthetic", List.of("skills"), "REVIEWED", "Engineer", description,
                new JobIntelligencePrompt.CanonicalRequirements(List.of(), List.of(), null),
                new GoldTruth(List.of(
                        new GoldSkill("Java", JobIntelligence.Importance.REQUIRED,
                                List.of(new EvidenceAnchor(JobIntelligence.Source.DESCRIPTION, "Java is required.", 1))),
                        new GoldSkill("Docker", JobIntelligence.Importance.PREFERRED,
                                List.of(new EvidenceAnchor(JobIntelligence.Source.DESCRIPTION, "Docker is preferred.", 1)))),
                        List.of(), List.of(), new GoldMinimumExperience(JobIntelligence.MinimumStatus.NOT_STATED, null)));
    }

    private static JobIntelligence intelligence(List<JobIntelligence.Skill> skills,
            List<JobIntelligence.Evidence> evidence) {
        return new JobIntelligence(new JobIntelligence.Facts(skills, List.of(), List.of()),
                new JobIntelligence.Interpretations(null, null, List.of(), List.of()), evidence, List.of(),
                new JobIntelligence.MinimumExperience(JobIntelligence.MinimumStatus.NOT_STATED, null));
    }

    private static EvaluatedCase compare(EvaluationCase value, JobIntelligence intelligence) {
        JobIntelligenceResult.AttemptMetadata metadata = new JobIntelligenceResult.AttemptMetadata(
                JobIntelligenceResult.Strategy.HYBRID_ENRICHMENT, true, "fixture", "fixture", "fixture",
                null, null, null, new JobIntelligenceModel.GenerationSettings(0.0, 1), Duration.ofSeconds(1),
                Duration.ZERO, Duration.ZERO, new JobIntelligenceModel.Usage(0L, 0L, 0L),
                JobIntelligenceModel.Outcome.COMPLETED, null, null);
        IntelligenceExecution ai = new IntelligenceExecution(new JobIntelligenceResult.Accepted(intelligence, metadata), 1);
        BaselineExecution baseline = new BaselineExecution(new JobExtractionBaseline.Result(
                JobExtractionBaseline.System.JC005, JobExtractionBaseline.Status.SUCCESS,
                new JobRequirementsResponse(List.of(), List.of(), null), null));
        return new SemanticComparator("test", "test").evaluate(
                new EvaluationCaseExecution(value, baseline, ai, ai));
    }

    private static EvaluatedCase caseById(EvaluationReport report, String id) {
        return report.cases().stream().filter(value -> value.caseId().equals(id)).findFirst().orElseThrow();
    }
}
