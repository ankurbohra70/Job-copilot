package com.jobcopilot.intelligence.openai;

import com.jobcopilot.intelligence.JobIntelligenceModel;
import com.jobcopilot.intelligence.JobIntelligencePrompt;
import com.jobcopilot.intelligence.JobIntelligenceResult;
import com.jobcopilot.intelligence.JobIntelligenceRunner;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.opentest4j.AssertionFailedError;
import static com.jobcopilot.intelligence.openai.OpenAiJobIntelligenceLiveSmokeTest.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OpenAiJobIntelligenceSmokeObservabilityTest {
    @ParameterizedTest @ValueSource(strings = {"transient_http", "transport_io", "response_failed"})
    void retryableCategorySurvivesInSmokeAssertionWithoutRetry(String code) {
        checkFailureDiagnostic(code, code);
    }

    @ParameterizedTest @MethodSource("unsafeCodes")
    void arbitraryOrMissingCodeCannotReachSmokeAssertion(String code) {
        checkFailureDiagnostic(code, "unavailable");
    }

    static Stream<String> unsafeCodes() {
        return Stream.of(null, "", "PRIVATE_SECRET", "transient_http\nPRIVATE_HEADER",
                "PRIVATE_BODY".repeat(10000));
    }

    private void checkFailureDiagnostic(String code, String expectedCode) {
        var delegate = mock(JobIntelligenceModel.class);
        when(delegate.providerId()).thenReturn("scripted");
        when(delegate.analyze(any())).thenReturn(new JobIntelligenceModel.ModelAttemptResult(
                "PRIVATE_CANDIDATE", JobIntelligenceModel.Outcome.RETRYABLE_FAILURE, code, null, null, null, null));
        var capture = new FailureCapture(delegate);
        var failed = assertInstanceOf(JobIntelligenceResult.Failed.class, run(capture, "PRIVATE_JD"));
        assertEquals(JobIntelligenceResult.Stage.EXECUTION, failed.failure().stage());
        assertEquals(JobIntelligenceResult.Code.PROVIDER_RETRYABLE_FAILURE, failed.failure().code());
        var assertion = assertThrows(AssertionFailedError.class, () -> assertProviderCompleted(failed, capture));
        assertEquals("failureCode=" + expectedCode + " ==> expected: <COMPLETED> but was: <RETRYABLE_FAILURE>",
                assertion.getMessage());
        assertFalse(assertion.getMessage().contains("PRIVATE_"));
        verify(delegate).providerId();
        verify(delegate).analyze(any());
        verifyNoMoreInteractions(delegate);
    }

    @Test void forwardingPreservesExactInputAndResultInstances() {
        var delegate = mock(JobIntelligenceModel.class);
        var expected = new JobIntelligenceModel.ModelAttemptResult("PRIVATE_CANDIDATE",
                JobIntelligenceModel.Outcome.COMPLETED, null, null, null, null, null);
        var input = new JobIntelligencePrompt().assemble(request("Must have Java.").strategyInput(), settings());
        when(delegate.analyze(input)).thenReturn(expected);
        assertSame(expected, new FailureCapture(delegate).analyze(input));
        verify(delegate).analyze(same(input));
        verifyNoMoreInteractions(delegate);
    }

    @ParameterizedTest @ValueSource(strings = {"success", "decode", "validation"})
    void capturePreservesExistingPipelineSemantics(String scenario) throws Exception {
        String candidate;
        try (var stream = getClass().getResourceAsStream("/job-intelligence/fixtures/valid-llm-first.json")) {
            candidate = scenario.equals("decode") ? "{" : new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        String description = scenario.equals("validation") ? "Must have Python." : "Must have Java.";
        var delegate = mock(JobIntelligenceModel.class);
        when(delegate.providerId()).thenReturn("scripted");
        when(delegate.analyze(any())).thenReturn(new JobIntelligenceModel.ModelAttemptResult(
                candidate, JobIntelligenceModel.Outcome.COMPLETED, null, null, null, null, null));
        var capture = new FailureCapture(delegate);
        var result = run(capture, description);
        assertProviderCompleted(result, capture);
        if (scenario.equals("success")) {
            assertInstanceOf(JobIntelligenceResult.Accepted.class, result);
        } else {
            var failed = assertInstanceOf(JobIntelligenceResult.Failed.class, result);
            assertEquals(scenario.equals("decode") ? JobIntelligenceResult.Stage.DECODE
                    : JobIntelligenceResult.Stage.VALIDATION, failed.failure().stage());
            assertEquals(scenario.equals("decode") ? JobIntelligenceResult.Code.MALFORMED_JSON
                    : JobIntelligenceResult.Code.EVIDENCE_QUOTE_MISMATCH, failed.failure().code());
        }
        verify(delegate).analyze(any());
    }

    @Test void unexpectedDelegateDefectIsNotCaughtOrRetried() {
        var delegate = mock(JobIntelligenceModel.class);
        var defect = new IllegalStateException("PRIVATE_EXCEPTION");
        when(delegate.analyze(any())).thenThrow(defect);
        var input = new JobIntelligencePrompt().assemble(request("D").strategyInput(), settings());
        assertSame(defect, assertThrows(IllegalStateException.class, () -> new FailureCapture(delegate).analyze(input)));
        verify(delegate).analyze(same(input));
        verifyNoMoreInteractions(delegate);
    }

    @Test void nullDelegateResultRemainsRunnerContractFailureWithSafeDiagnostic() {
        var delegate = mock(JobIntelligenceModel.class);
        when(delegate.providerId()).thenReturn("scripted");
        var capture = new FailureCapture(delegate);
        var failed = assertInstanceOf(JobIntelligenceResult.Failed.class, run(capture, "D"));
        assertEquals(JobIntelligenceResult.Code.PROVIDER_CONTRACT_VIOLATION, failed.failure().code());
        var assertion = assertThrows(AssertionFailedError.class, () -> assertProviderCompleted(failed, capture));
        assertTrue(assertion.getMessage().startsWith("failureCode=unavailable"));
        verify(delegate).analyze(any());
    }

    @Test void liveClassStillRequiresExplicitEnvironmentOptIn() {
        var gate = OpenAiJobIntelligenceLiveSmokeTest.class.getAnnotation(EnabledIfEnvironmentVariable.class);
        assertNotNull(gate);
        assertEquals("JOB_INTELLIGENCE_LIVE_TEST", gate.named());
        assertEquals("(?i:true)", gate.matches());
    }

    private static JobIntelligenceResult run(JobIntelligenceModel model, String description) {
        return new JobIntelligenceRunner(new JobIntelligencePrompt(), model).run(request(description));
    }

    private static JobIntelligenceRunner.Request request(String description) {
        return new JobIntelligenceRunner.Request(new JobIntelligencePrompt.LlmFirstInput("Engineer", description), settings());
    }

    private static JobIntelligencePrompt.ExecutionSettings settings() {
        return new JobIntelligencePrompt.ExecutionSettings("test-model",
                new JobIntelligenceModel.GenerationSettings(null, 600), Duration.ofSeconds(3));
    }
}
