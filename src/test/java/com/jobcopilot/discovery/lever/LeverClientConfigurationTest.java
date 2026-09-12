package com.jobcopilot.discovery.lever;

import java.time.Duration;
import okhttp3.Authenticator;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;

class LeverClientConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(LeverClientConfiguration.class);

    @Test void defaultsWireOneSafeGatewayWithoutNetworkActivity() {
        context.run(result -> {
            assertThat(result).hasNotFailed();
            assertThat(result).hasSingleBean(LeverPostingGateway.class);
            assertThat(result).hasSingleBean(OkHttpClient.class);
            LeverClientProperties properties = result.getBean(LeverClientProperties.class);
            assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(5));
            assertThat(properties.requestTimeout()).isEqualTo(Duration.ofSeconds(20));
            assertThat(properties.maxResponseBytes()).isEqualTo(10_485_760L);
            assertSafe(result.getBean(OkHttpClient.class));
        });
    }

    @Test void overridesBindAndConfigureTransport() {
        context.withPropertyValues("job-discovery.lever.connect-timeout=2s",
                "job-discovery.lever.request-timeout=7s", "job-discovery.lever.max-response-bytes=4096")
                .run(result -> {
                    assertThat(result).hasNotFailed();
                    LeverClientProperties properties = result.getBean(LeverClientProperties.class);
                    assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(2));
                    assertThat(properties.requestTimeout()).isEqualTo(Duration.ofSeconds(7));
                    assertThat(properties.maxResponseBytes()).isEqualTo(4096L);
                    OkHttpClient client = result.getBean(OkHttpClient.class);
                    assertThat(client.connectTimeoutMillis()).isEqualTo(2000);
                    assertThat(client.readTimeoutMillis()).isEqualTo(7000);
                    assertThat(client.callTimeoutMillis()).isEqualTo(7000);
                });
    }

    @Test void invalidConfigurationFailsStartup() {
        context.withPropertyValues("job-discovery.lever.connect-timeout=0s").run(result ->
                assertThat(result).hasFailed().getFailure().hasRootCauseMessage(
                        "job-discovery.lever.connect-timeout must be positive"));
        context.withPropertyValues("job-discovery.lever.request-timeout=-1s").run(result ->
                assertThat(result).hasFailed().getFailure().hasRootCauseMessage(
                        "job-discovery.lever.request-timeout must be positive"));
        context.withPropertyValues("job-discovery.lever.max-response-bytes=0").run(result ->
                assertThat(result).hasFailed().getFailure().hasRootCauseMessage(
                        "job-discovery.lever.max-response-bytes must be between 1 and " + Integer.MAX_VALUE));
        context.withPropertyValues("job-discovery.lever.max-response-bytes=-1").run(result ->
                assertThat(result).hasFailed().getFailure().hasRootCauseMessage(
                        "job-discovery.lever.max-response-bytes must be between 1 and " + Integer.MAX_VALUE));
        context.withPropertyValues("job-discovery.lever.max-response-bytes=2147483648").run(result ->
                assertThat(result).hasFailed().getFailure().hasRootCauseMessage(
                        "job-discovery.lever.max-response-bytes must be between 1 and " + Integer.MAX_VALUE));
        context.withPropertyValues("job-discovery.lever.connect-timeout=not-a-duration").run(result ->
                assertThat(result).hasFailed());
        context.withPropertyValues("job-discovery.lever.connect-timeout=1ns").run(result ->
                assertThat(result).hasFailed().getFailure().hasRootCauseMessage(
                        "job-discovery.lever.connect-timeout must be between 1ms and " + Integer.MAX_VALUE + "ms"));
        context.withPropertyValues("job-discovery.lever.request-timeout=2147483648ms").run(result ->
                assertThat(result).hasFailed().getFailure().hasRootCauseMessage(
                        "job-discovery.lever.request-timeout must be between 1ms and " + Integer.MAX_VALUE + "ms"));
    }

    private static void assertSafe(OkHttpClient client) {
        assertThat(client.retryOnConnectionFailure()).isFalse();
        assertThat(client.followRedirects()).isFalse();
        assertThat(client.followSslRedirects()).isFalse();
        assertThat(client.authenticator()).isSameAs(Authenticator.NONE);
        assertThat(client.proxyAuthenticator()).isSameAs(Authenticator.NONE);
        assertThat(client.cache()).isNull();
        assertThat(client.interceptors()).isEmpty();
        assertThat(client.networkInterceptors()).hasSize(1);
        assertThat(client.protocols()).containsExactly(okhttp3.Protocol.HTTP_1_1);
    }
}
