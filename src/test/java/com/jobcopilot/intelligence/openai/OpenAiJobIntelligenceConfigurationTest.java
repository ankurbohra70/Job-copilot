package com.jobcopilot.intelligence.openai;

import com.jobcopilot.intelligence.JobIntelligenceModel;
import com.jobcopilot.intelligence.JobIntelligencePrompt;
import com.jobcopilot.intelligence.JobIntelligenceRunner;
import com.openai.client.OpenAIClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;

class OpenAiJobIntelligenceConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(OpenAiJobIntelligenceConfiguration.class);

    @Test void disabledWithoutKeyStartsWithoutProviderBeans() {
        context.run(result -> {
            assertThat(result).hasNotFailed();
            assertThat(result).doesNotHaveBean(OpenAIClient.class);
            assertThat(result).doesNotHaveBean(JobIntelligenceModel.class);
            assertThat(result).doesNotHaveBean(JobIntelligenceRunner.class);
        });
    }

    @Test void keyAloneDoesNotEnableProvider() {
        context.withPropertyValues("job-intelligence.openai.api-key=dummy-key").run(result -> {
            assertThat(result).hasNotFailed();
            assertThat(result).doesNotHaveBean(OpenAIClient.class);
        });
    }

    @Test void enabledBlankKeyFailsSafely() {
        context.withPropertyValues("job-intelligence.openai.enabled=true").run(result -> {
            assertThat(result).hasFailed();
            assertThat(result.getStartupFailure()).hasRootCauseMessage(
                    "job-intelligence.openai.api-key must be configured when OpenAI is enabled");
        });
    }

    @Test void enabledInvalidBaseUrlFailsSafely() {
        context.withPropertyValues("job-intelligence.openai.enabled=true", "job-intelligence.openai.api-key=dummy",
                "job-intelligence.openai.base-url=not-a-url").run(result -> {
            assertThat(result).hasFailed();
            assertThat(result.getStartupFailure()).hasRootCauseMessage(
                    "job-intelligence.openai.base-url must be an absolute HTTP(S) URL when OpenAI is enabled");
        });
    }

    @Test void enabledNonpositiveConnectTimeoutFailsSafely() {
        context.withPropertyValues("job-intelligence.openai.enabled=true", "job-intelligence.openai.api-key=dummy",
                "job-intelligence.openai.connect-timeout=0s").run(result -> {
            assertThat(result).hasFailed();
            assertThat(result.getStartupFailure()).hasRootCauseMessage(
                    "job-intelligence.openai.connect-timeout must be positive when OpenAI is enabled");
        });
    }

    @Test void enabledValidConfigurationWiresOneProviderWithoutNetworkCall() {
        context.withPropertyValues("job-intelligence.openai.enabled=true", "job-intelligence.openai.api-key=dummy",
                "job-intelligence.openai.base-url=https://example.invalid/v1", "job-intelligence.openai.connect-timeout=1s")
                .run(result -> {
                    assertThat(result).hasNotFailed();
                    assertThat(result).hasSingleBean(OpenAIClient.class);
                    assertThat(result).hasSingleBean(JobIntelligenceModel.class);
                    assertThat(result).hasSingleBean(JobIntelligencePrompt.class);
                    assertThat(result).hasSingleBean(JobIntelligenceRunner.class);
                });
    }

    @Test void propertiesNeverRenderApiKey() {
        var properties = new OpenAiJobIntelligenceProperties(true, "SECRET_CANARY", "https://api.openai.com/v1", java.time.Duration.ofSeconds(1));
        assertThat(properties.toString()).doesNotContain("SECRET_CANARY").contains("<redacted>");
    }

    @Test void existingModelDoesNotCreateAmbiguousRunnerDependency() {
        context.withBean(JobIntelligenceModel.class, () -> org.mockito.Mockito.mock(JobIntelligenceModel.class))
                .withPropertyValues("job-intelligence.openai.enabled=true", "job-intelligence.openai.api-key=dummy")
                .run(result -> {
                    assertThat(result).hasNotFailed();
                    assertThat(result).hasSingleBean(JobIntelligenceModel.class);
                    assertThat(result).hasSingleBean(JobIntelligenceRunner.class);
                });
    }

    @Test void effectiveTransportDisablesRecoveryRedirectsAndAuthentication() {
        var transport = OpenAiJobIntelligenceConfiguration.transport(java.time.Duration.ofSeconds(1));
        try {
            assertThat(transport.retryOnConnectionFailure()).isFalse();
            assertThat(transport.followRedirects()).isFalse();
            assertThat(transport.followSslRedirects()).isFalse();
            assertThat(transport.authenticator()).isSameAs(okhttp3.Authenticator.NONE);
            assertThat(transport.proxyAuthenticator()).isSameAs(okhttp3.Authenticator.NONE);
            assertThat(transport.networkInterceptors()).isEmpty();
            assertThat(transport.interceptors()).hasSize(1);
        } finally {
            transport.dispatcher().executorService().shutdown();
            transport.connectionPool().evictAll();
        }
    }
}
