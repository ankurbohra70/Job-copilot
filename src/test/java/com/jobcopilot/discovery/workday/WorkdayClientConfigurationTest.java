package com.jobcopilot.discovery.workday;

import java.time.Duration;
import okhttp3.Authenticator;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;

class WorkdayClientConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(WorkdayClientConfiguration.class);

    @Test void defaultsWireOnePersistenceFreeSafeGateway() {
        context.run(result -> {
            assertThat(result).hasNotFailed().hasSingleBean(WorkdayPostingGateway.class);
            assertThat(result).doesNotHaveBean(javax.sql.DataSource.class);
            WorkdayClientProperties p = result.getBean(WorkdayClientProperties.class);
            assertThat(p.connectTimeout()).isEqualTo(Duration.ofSeconds(5));
            assertThat(p.requestTimeout()).isEqualTo(Duration.ofSeconds(20));
            assertThat(p.maxSearchResponseBytes()).isEqualTo(5_242_880);
            assertThat(p.maxDetailResponseBytes()).isEqualTo(2_097_152);
            OkHttpClient client = result.getBean(OkHttpClient.class);
            assertThat(client.retryOnConnectionFailure()).isFalse();
            assertThat(client.followRedirects()).isFalse();
            assertThat(client.followSslRedirects()).isFalse();
            assertThat(client.authenticator()).isSameAs(Authenticator.NONE);
            assertThat(client.proxyAuthenticator()).isSameAs(Authenticator.NONE);
            assertThat(client.cookieJar()).isSameAs(okhttp3.CookieJar.NO_COOKIES);
            assertThat(client.protocols()).containsExactly(okhttp3.Protocol.HTTP_1_1);
        });
    }

    @Test void overridesBindAndInvalidLimitsFailStartup() {
        context.withPropertyValues("job-discovery.workday.connect-timeout=2s",
                "job-discovery.workday.request-timeout=7s",
                "job-discovery.workday.max-search-response-bytes=4096",
                "job-discovery.workday.max-detail-response-bytes=2048").run(result -> {
                    assertThat(result).hasNotFailed();
                    WorkdayClientProperties p = result.getBean(WorkdayClientProperties.class);
                    assertThat(p.maxSearchResponseBytes()).isEqualTo(4096);
                    assertThat(p.maxDetailResponseBytes()).isEqualTo(2048);
                });
        context.withPropertyValues("job-discovery.workday.connect-timeout=0s")
                .run(result -> assertThat(result).hasFailed());
        context.withPropertyValues("job-discovery.workday.request-timeout=1ns")
                .run(result -> assertThat(result).hasFailed());
        context.withPropertyValues("job-discovery.workday.max-search-response-bytes=0")
                .run(result -> assertThat(result).hasFailed());
        context.withPropertyValues("job-discovery.workday.max-detail-response-bytes=2147483648")
                .run(result -> assertThat(result).hasFailed());
    }
}
