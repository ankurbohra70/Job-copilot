package com.jobcopilot.intelligence.evaluation;

import com.jobcopilot.intelligence.JobIntelligenceModel;
import com.jobcopilot.intelligence.JobIntelligencePrompt;
import com.jobcopilot.intelligence.JobIntelligenceResult;
import com.jobcopilot.intelligence.JobIntelligenceRunner;
import com.jobcopilot.job.JobExtractionBaseline;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import static com.jobcopilot.intelligence.evaluation.EvaluationResult.*;

final class EvaluationCoordinator {
    @FunctionalInterface
    interface ModelSource {
        JobIntelligenceModel modelFor(String caseId, Strategy strategy);
    }

    private final JobExtractionBaseline baseline;
    private final JobIntelligencePrompt prompt;
    private final ModelSource models;
    private final JobIntelligencePrompt.ExecutionSettings settings;

    EvaluationCoordinator(JobExtractionBaseline baseline, JobIntelligencePrompt prompt, ModelSource models,
            JobIntelligencePrompt.ExecutionSettings settings) {
        this.baseline = Objects.requireNonNull(baseline);
        this.prompt = Objects.requireNonNull(prompt);
        this.models = Objects.requireNonNull(models);
        this.settings = Objects.requireNonNull(settings);
    }

    EvaluationCaseExecution execute(EvaluationCase evaluationCase) {
        Objects.requireNonNull(evaluationCase);
        RawExecution baselineExecution;
        try {
            baselineExecution = new BaselineExecution(baseline.capture(evaluationCase.description()));
        } catch (RuntimeException defect) {
            baselineExecution = defect(defect);
        }

        RawExecution hybrid = executeAi(evaluationCase, Strategy.HYBRID_ENRICHMENT,
                new JobIntelligencePrompt.HybridInput(evaluationCase.title(), evaluationCase.description(),
                        evaluationCase.canonicalRequirements()));
        RawExecution llmFirst = executeAi(evaluationCase, Strategy.LLM_FIRST,
                new JobIntelligencePrompt.LlmFirstInput(evaluationCase.title(), evaluationCase.description()));
        return new EvaluationCaseExecution(evaluationCase, baselineExecution, hybrid, llmFirst);
    }

    private RawExecution executeAi(EvaluationCase evaluationCase, Strategy strategy,
            JobIntelligencePrompt.StrategyInput input) {
        AtomicInteger invocations = new AtomicInteger();
        try {
            JobIntelligenceModel delegate = Objects.requireNonNull(models.modelFor(evaluationCase.id(), strategy));
            JobIntelligenceModel counted = new JobIntelligenceModel() {
                @Override public String providerId() { return delegate.providerId(); }
                @Override public ModelAttemptResult analyze(ModelInput modelInput) {
                    invocations.incrementAndGet();
                    return delegate.analyze(modelInput);
                }
            };
            JobIntelligenceResult result = new JobIntelligenceRunner(prompt, counted)
                    .run(new JobIntelligenceRunner.Request(input, settings));
            return new IntelligenceExecution(result, invocations.get());
        } catch (RuntimeException defect) {
            return defect(defect);
        }
    }

    private static DefectExecution defect(RuntimeException defect) {
        String type = defect.getClass().getSimpleName();
        if (type.isEmpty() || type.length() > 80 || !type.matches("[A-Za-z][A-Za-z0-9]*")) type = "RuntimeException";
        return new DefectExecution(type);
    }
}
