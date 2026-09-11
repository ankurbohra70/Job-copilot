package com.jobcopilot.intelligence.gemini;

import com.jobcopilot.intelligence.JobIntelligenceModel;
import com.jobcopilot.intelligence.JobIntelligencePrompt;
import com.jobcopilot.intelligence.JobIntelligenceRunner;
import java.net.http.HttpClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;

class GeminiJobIntelligenceConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(GeminiJobIntelligenceConfiguration.class);

    @Test void disabledWithoutKeyStartsWithoutProviderBeans() {
        context.run(result -> {
            assertThat(result).hasNotFailed();
            assertThat(result).doesNotHaveBean(HttpClient.class);
            assertThat(result).doesNotHaveBean(JobIntelligenceModel.class);
            assertThat(result).doesNotHaveBean(JobIntelligenceRunner.class);
        });
    }

    @Test void keyAloneDoesNotEnableProvider() {
        context.withPropertyValues("job-intelligence.gemini.api-key=dummy-key").run(result -> {
            assertThat(result).hasNotFailed();
            assertThat(result).doesNotHaveBean(HttpClient.class);
        });
    }

    @Test void enabledInvalidSettingsFailSafely() {
        context.withPropertyValues("job-intelligence.gemini.enabled=true").run(result ->
                assertThat(result).hasFailed().getFailure().hasRootCauseMessage(
                        "job-intelligence.gemini.api-key must be configured when Gemini is enabled"));
        context.withPropertyValues("job-intelligence.gemini.enabled=true", "job-intelligence.gemini.api-key=dummy",
                "job-intelligence.gemini.connect-timeout=0s").run(result ->
                assertThat(result).hasFailed().getFailure().hasRootCauseMessage(
                        "job-intelligence.gemini.connect-timeout must be positive when Gemini is enabled"));
        context.withPropertyValues("job-intelligence.gemini.enabled=true", "job-intelligence.gemini.api-key=dummy",
                "job-intelligence.gemini.base-url=not-a-url").run(result ->
                assertThat(result).hasFailed().getFailure().hasRootCauseMessage(
                        "job-intelligence.gemini.base-url must be an absolute HTTP(S) URL when Gemini is enabled"));
    }

    @Test void enabledValidConfigurationWiresExistingBoundaryWithoutNetwork() {
        context.withPropertyValues("job-intelligence.gemini.enabled=true", "job-intelligence.gemini.api-key=dummy",
                "job-intelligence.gemini.base-url=https://example.invalid", "job-intelligence.gemini.connect-timeout=1s")
                .run(result -> {
                    assertThat(result).hasNotFailed();
                    assertThat(result).hasSingleBean(HttpClient.class);
                    assertThat(result).hasSingleBean(JobIntelligenceModel.class);
                    assertThat(result).hasSingleBean(JobIntelligencePrompt.class);
                    assertThat(result).hasSingleBean(JobIntelligenceRunner.class);
                });
    }

    @Test void propertiesNeverRenderApiKey() {
        var properties = new GeminiJobIntelligenceProperties(true, "SECRET_CANARY", "https://example.invalid",
                java.time.Duration.ofSeconds(1));
        assertThat(properties.toString()).doesNotContain("SECRET_CANARY").contains("<redacted>");
    }
}
