package com.jobcopilot.intelligence.openai;

import com.jobcopilot.intelligence.JobIntelligenceModel;
import com.jobcopilot.intelligence.JobIntelligencePrompt;
import com.jobcopilot.intelligence.JobIntelligenceResult;
import com.jobcopilot.intelligence.JobIntelligenceRunner;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "JOB_INTELLIGENCE_LIVE_TEST", matches = "(?i:true)")
class OpenAiJobIntelligenceLiveSmokeTest {
    @Test void oneRealCallTraversesTheExistingPipeline() {
        String key = System.getenv("OPENAI_API_KEY");
        assertNotNull(key, "OPENAI_API_KEY is required when JOB_INTELLIGENCE_LIVE_TEST=true");
        assertFalse(key.isBlank(), "OPENAI_API_KEY is required when JOB_INTELLIGENCE_LIVE_TEST=true");
        String model = System.getenv().getOrDefault("OPENAI_MODEL", "gpt-5.6-terra");
        var client = new OpenAiJobIntelligenceConfiguration().openAiClient(new OpenAiJobIntelligenceProperties(
                true, key, "https://api.openai.com/v1", Duration.ofSeconds(5)));
        try {
            var capture = new FailureCapture(new OpenAiJobIntelligenceModel(client));
            var runner = new JobIntelligenceRunner(new JobIntelligencePrompt(), capture);
            var request = new JobIntelligenceRunner.Request(
                    new JobIntelligencePrompt.LlmFirstInput("Backend Engineer",
                            "Must have Java. Two years of backend development experience required."),
                    new JobIntelligencePrompt.ExecutionSettings(model,
                            new JobIntelligenceModel.GenerationSettings(null, 1800), Duration.ofSeconds(45)));
            JobIntelligenceResult result = runner.run(request);
            assertTrue(result.metadata().invocationStarted());
            assertEquals("openai", result.metadata().providerId());
            assertProviderCompleted(result, capture);
            if (result instanceof JobIntelligenceResult.Failed failed) {
                assertNotEquals(JobIntelligenceResult.Stage.EXECUTION, failed.failure().stage());
            }
        } finally {
            client.close();
        }
    }

    static void assertProviderCompleted(JobIntelligenceResult result, FailureCapture capture) {
        assertEquals(JobIntelligenceModel.Outcome.COMPLETED, result.metadata().providerOutcome(),
                () -> "failureCode=" + capture.failureCode);
    }

    /** Test-only observation; never retain a candidate or arbitrary provider text for diagnostics. */
    static final class FailureCapture implements JobIntelligenceModel {
        private final JobIntelligenceModel delegate;
        private volatile String failureCode = "unavailable";

        FailureCapture(JobIntelligenceModel delegate) { this.delegate = java.util.Objects.requireNonNull(delegate); }

        @Override public String providerId() { return delegate.providerId(); }

        @Override public ModelAttemptResult analyze(ModelInput input) {
            ModelAttemptResult result = delegate.analyze(input);
            failureCode = switch (result == null ? null : result.failureCode()) {
                case "transient_http" -> "transient_http";
                case "transport_io" -> "transport_io";
                case "response_failed" -> "response_failed";
                case null, default -> "unavailable";
            };
            return result;
        }
    }
}
