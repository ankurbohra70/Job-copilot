package com.jobcopilot.discovery.lever;

import java.time.Duration;
import java.util.List;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIfSystemProperty(named = "jobcopilot.lever.live", matches = "true")
class LeverPostingGatewayLiveAcceptanceTest {
    @Test void globalAndEuPublicFeedsTraverseTheProductionBoundary() {
        OkHttpClient client = LeverClientConfiguration.transport(Duration.ofSeconds(5), Duration.ofSeconds(20));
        try {
            LeverPostingGateway gateway = new HttpLeverPostingGateway(client, new LeverEndpointResolver(),
                    new LeverResponseDecoder(), 10_485_760);
            accept(gateway, LeverRegion.GLOBAL,
                    System.getProperty("jobcopilot.lever.live.global-site", "leverdemo"), "jobs.lever.co");
            accept(gateway, LeverRegion.EU,
                    System.getProperty("jobcopilot.lever.live.eu-site", "leverdemo"), "jobs.eu.lever.co");
        } finally {
            client.dispatcher().executorService().shutdownNow();
            client.connectionPool().evictAll();
        }
    }

    private static void accept(LeverPostingGateway gateway, LeverRegion region, String sourceKey,
            String expectedJobsHost) {
        LeverFetchResult result = gateway.fetchPage(new LeverSource(region, sourceKey), new LeverPageRequest(0, 10));
        assertThat(result).as(region + " real Lever result").isInstanceOf(LeverFetchResult.Success.class);
        var success = (LeverFetchResult.Success) result;
        assertThat(success.postings()).isNotEmpty();
        assertThat(success.postings()).allSatisfy(posting -> {
            assertThat(posting.externalId()).isNotBlank();
            assertThat(posting.title()).isNotBlank();
            assertThat(posting.hostedUrl().isAbsolute()).isTrue();
            assertThat(posting.hostedUrl().getScheme()).isEqualToIgnoringCase("https");
            assertThat(posting.hostedUrl().getHost()).isEqualTo(expectedJobsHost);
            assertThat(posting.applyUrl().isAbsolute()).isTrue();
            assertThat(posting.applyUrl().getScheme()).isEqualToIgnoringCase("https");
        });
        assertThat(success.postings()).anySatisfy(posting -> assertThat(List.of(
                        safe(posting.content().descriptionHtml()), safe(posting.content().descriptionPlain()),
                        safe(posting.content().additionalHtml()), safe(posting.content().additionalPlain()),
                        posting.content().sections().isEmpty() ? "" : posting.content().sections().getFirst().html()))
                .anyMatch(value -> !value.isBlank()));
        assertThatThrownBy(() -> success.postings().clear()).isInstanceOf(UnsupportedOperationException.class);
        System.out.println("LEVER_ACCEPTANCE region=" + region + " outcome=SUCCESS count="
                + success.postings().size() + " latencyMs=" + success.latency().toMillis());
    }

    private static String safe(String value) { return value == null ? "" : value; }
}
