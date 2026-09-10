package com.jobcopilot.intelligence.openai;

import com.jobcopilot.intelligence.JobIntelligenceModel;
import com.jobcopilot.intelligence.JobIntelligencePrompt;
import com.jobcopilot.intelligence.JobIntelligenceResult;
import com.jobcopilot.intelligence.JobIntelligenceRunner;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.LogLevel;
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
            var runner = new JobIntelligenceRunner(new JobIntelligencePrompt(), new OpenAiJobIntelligenceModel(client));
            var request = new JobIntelligenceRunner.Request(
                    new JobIntelligencePrompt.LlmFirstInput("Backend Engineer",
                            "Must have Java. Two years of backend development experience required."),
                    new JobIntelligencePrompt.ExecutionSettings(model,
                            new JobIntelligenceModel.GenerationSettings(null, 1800), Duration.ofSeconds(45)));
            JobIntelligenceResult result = runner.run(request);
            assertTrue(result.metadata().invocationStarted());
            assertEquals("openai", result.metadata().providerId());
            assertEquals(JobIntelligenceModel.Outcome.COMPLETED, result.metadata().providerOutcome());
            if (result instanceof JobIntelligenceResult.Failed failed) {
                assertNotEquals(JobIntelligenceResult.Stage.EXECUTION, failed.failure().stage());
            }
        } finally {
            client.close();
        }
    }
}
