package com.jobcopilot.intelligence;

import com.jobcopilot.job.JobExtractionBaseline;
import com.jobcopilot.job.JobRequirementExtractor;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import com.jobcopilot.intelligence.JobIntelligencePrompt.CanonicalRequirements;
import com.jobcopilot.intelligence.JobIntelligencePrompt.HybridInput;
import com.jobcopilot.intelligence.JobIntelligencePrompt.LlmFirstInput;
import com.jobcopilot.intelligence.JobIntelligenceResult.Code;
import com.jobcopilot.intelligence.JobIntelligenceResult.Failed;
import com.jobcopilot.intelligence.JobIntelligenceResult.Stage;
import static com.jobcopilot.intelligence.JobIntelligenceTestSupport.ScriptedModel;
import static com.jobcopilot.intelligence.JobIntelligenceTestSupport.javaRequired;
import static com.jobcopilot.intelligence.JobIntelligenceTestSupport.request;
import static com.jobcopilot.intelligence.JobIntelligenceTestSupport.runner;
import static org.junit.jupiter.api.Assertions.*;

class JobIntelligenceRunnerTest {
    @AfterEach void clearInterrupt() {
        Thread.interrupted();
    }

    @Test void validLlmFirstCandidateIsAccepted() {
        var model = new ScriptedModel(javaRequired());
        var result = runner(model).run(request(new LlmFirstInput("Engineer", "Must have Java.")));
        assertInstanceOf(JobIntelligenceResult.Accepted.class, result);
        var accepted = (JobIntelligenceResult.Accepted) result;
        assertEquals("Java", accepted.intelligence().facts().skills().getFirst().name());
        assertEquals(JobIntelligenceResult.Strategy.LLM_FIRST, accepted.metadata().strategy());
        assertTrue(accepted.metadata().invocationStarted());
        assertEquals("scripted", accepted.metadata().providerId());
        assertEquals("test-model", accepted.metadata().requestedModel());
        assertEquals("returned-model", accepted.metadata().returnedModel());
        assertEquals(JobIntelligenceModel.Outcome.COMPLETED, accepted.metadata().providerOutcome());
        assertEquals(1, model.invocations());
        assertNull(accepted.intelligence().minimumExperience().months());
    }

    @Test void validHybridCandidateIsAccepted() {
        var model = new ScriptedModel(javaRequired());
        var result = runner(model).run(request(new HybridInput("Engineer", "Must have Java.",
                new CanonicalRequirements(List.of("Java"), List.of(), null))));
        assertInstanceOf(JobIntelligenceResult.Accepted.class, result);
        assertEquals(JobIntelligenceResult.Strategy.HYBRID_ENRICHMENT, result.metadata().strategy());
        assertEquals(1, model.invocations());
    }

    @Test void completedProviderOutputCanStillFailValidation() {
        var model = new ScriptedModel(javaRequired());
        var result = runner(model).run(request(new LlmFirstInput("Engineer", "Unrelated description.")));
        assertInstanceOf(Failed.class, result);
        var failed = (Failed) result;
        assertEquals(Stage.VALIDATION, failed.failure().stage());
        assertEquals(Code.EVIDENCE_QUOTE_MISMATCH, failed.failure().code());
        assertEquals(JobIntelligenceModel.Outcome.COMPLETED, failed.metadata().providerOutcome());
        assertEquals(1, model.invocations());
    }

    @ParameterizedTest
    @EnumSource(value = JobIntelligenceModel.Outcome.class, names = {"REFUSED", "INCOMPLETE", "RETRYABLE_FAILURE", "PERMANENT_FAILURE"})
    void nonCompletedOutcomesAreTerminalExecutionFailures(JobIntelligenceModel.Outcome outcome) {
        var model = new ScriptedModel(ScriptedModel.outcome(outcome));
        var result = runner(model).run(request(new LlmFirstInput("Engineer", "Must have Java.")));
        assertInstanceOf(Failed.class, result);
        var failed = (Failed) result;
        assertEquals(Stage.EXECUTION, failed.failure().stage());
        assertEquals(1, model.invocations());
        assertEquals(outcome, failed.metadata().providerOutcome());
    }

    @Test void retryableOutcomeIsNotRetried() {
        var model = new ScriptedModel(ScriptedModel.outcome(JobIntelligenceModel.Outcome.RETRYABLE_FAILURE));
        runner(model).run(request(new LlmFirstInput("Title", "Description")));
        assertEquals(1, model.invocations());
    }

    @Test void preflightRejectionDoesNotInvokeModel() {
        var model = new ScriptedModel(javaRequired());
        var result = runner(model).run(new JobIntelligenceRunner.Request(new LlmFirstInput("T", "D"),
                new JobIntelligencePrompt.ExecutionSettings("m", new JobIntelligenceModel.GenerationSettings(0.0, 1), Duration.ZERO)));
        assertEquals(Code.INVALID_TIMEOUT, ((Failed) result).failure().code());
        assertEquals(Stage.PREFLIGHT, ((Failed) result).failure().stage());
        assertFalse(result.metadata().invocationStarted());
        assertEquals(0, model.invocations());
    }

    @Test void timeoutIsTerminalEvenIfModelWouldSucceed() {
        var model = new ScriptedModel(javaRequired());
        var result = runner(model, (Future<?> future, long nanos) -> JobIntelligenceAttempt.Waiter.Outcome.TIMEOUT)
                .run(request(new LlmFirstInput("Engineer", "Must have Java.")));
        assertEquals(Code.TIMEOUT, ((Failed) result).failure().code());
        assertEquals(Stage.EXECUTION, ((Failed) result).failure().stage());
        assertTrue(result.metadata().invocationStarted());
        assertEquals(1, model.invocations());
    }

    @Test void interruptionPreservesInterruptFlag() {
        var model = new ScriptedModel(javaRequired());
        var result = runner(model, (future, nanos) -> {
            Thread.currentThread().interrupt();
            return JobIntelligenceAttempt.Waiter.Outcome.INTERRUPTED;
        }).run(request(new LlmFirstInput("Engineer", "Must have Java.")));
        assertTrue(Thread.currentThread().isInterrupted());
        assertEquals(Code.INTERRUPTED, ((Failed) result).failure().code());
    }

    @Test void invocationExceptionIsBounded() {
        var model = new ScriptedModel(input -> { throw new IllegalStateException("provider exploded with secret"); });
        var result = runner(model).run(request(new LlmFirstInput("Engineer", "Must have Java.")));
        var failed = (Failed) result;
        assertEquals(Code.INVOCATION_EXCEPTION, failed.failure().code());
        assertEquals(Stage.EXECUTION, failed.failure().stage());
        assertFalse(failed.failure().location().path().contains("secret"));
        assertFalse(failed.toString().contains("provider exploded"));
    }

    @Test void errorIsNotMappedToProviderFailure() {
        var model = new ScriptedModel(input -> { throw new AssertionError("jvm"); });
        assertThrows(AssertionError.class, () -> runner(model).run(request(new LlmFirstInput("T", "D"))));
    }

    @Test void malformedCompletedJsonDoesNotBecomeAccepted() throws Exception {
        var model = new ScriptedModel("{");
        var result = runner(model).run(request(new LlmFirstInput("T", "D")));
        assertEquals(Stage.DECODE, ((Failed) result).failure().stage());
        assertEquals(JobIntelligenceModel.Outcome.COMPLETED, result.metadata().providerOutcome());
    }

    @Test void baselineCaptureDoesNotAffectModelExecution() {
        AtomicInteger invocations = new AtomicInteger();
        var model = new ScriptedModel(input -> {
            invocations.incrementAndGet();
            assertFalse(input.strategyData().contains("CANONICAL_SENTINEL"));
            return ScriptedModel.completed(javaRequired());
        });
        var runner = runner(model);
        new JobExtractionBaseline(new JobRequirementExtractor()).capture("Must have CANONICAL_SENTINEL.");
        var result = runner.run(request(new LlmFirstInput("Engineer", "Must have Java.")));
        assertInstanceOf(JobIntelligenceResult.Accepted.class, result);
        assertEquals(1, invocations.get());
    }

    @Test void canonicalChangesDoNotMakeUnsupportedHybridClaimsPass() {
        var model = new ScriptedModel(javaRequired());
        var missing = runner(model).run(request(new HybridInput("Role", "See requisition.",
                new CanonicalRequirements(List.of(), List.of(), null))));
        var withCanonical = runner(model).run(request(new HybridInput("Role", "See requisition.",
                new CanonicalRequirements(List.of("Java"), List.of(), new BigDecimal("5")))));
        assertEquals(Code.EVIDENCE_QUOTE_MISMATCH, ((Failed) missing).failure().code());
        assertEquals(Code.EVIDENCE_QUOTE_MISMATCH, ((Failed) withCanonical).failure().code());
    }

    @Test void nullableRawSourcesAreTreatedAsEmptyWithoutChangingContracts() {
        var model = new ScriptedModel(JobIntelligenceTestSupport.emptyFacts("", "", "",
                "{\"code\":\"INSUFFICIENT_CONTEXT\",\"target\":\"facts\",\"evidenceIds\":[]}"));
        var result = runner(model).run(request(new LlmFirstInput(null, null)));
        assertInstanceOf(JobIntelligenceResult.Accepted.class, result);
        assertEquals(List.of("title", "description"), java.util.Arrays.stream(LlmFirstInput.class.getRecordComponents()).map(c -> c.getName()).toList());
    }
}
