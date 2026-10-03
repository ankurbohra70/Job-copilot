package com.jobcopilot.discovery.workday;

import java.time.Duration;
import java.util.List;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named = "jobcopilot.workday.live", matches = "true")
class WorkdayPostingGatewayLiveAcceptanceTest {
    @Test void explicitlyConfiguredPublicSiteTraversesSearchAndDetailBoundary() {
        WorkdaySource source = new WorkdaySource(required("host"), required("tenant"),
                required("site"), required("locale"));
        OkHttpClient client = WorkdayClientConfiguration.transport(Duration.ofSeconds(5), Duration.ofSeconds(20));
        try {
            WorkdayPostingGateway gateway = new HttpWorkdayPostingGateway(client, new WorkdayEndpointResolver(),
                    new WorkdayResponseDecoder(), 5_242_880, 2_097_152);
            WorkdayFetchResult search = gateway.searchPage(source,
                    new WorkdayPageRequest(0, WorkdayPageRequest.MAX_LIMIT));
            assertThat(search).isInstanceOf(WorkdayFetchResult.SearchSuccess.class);
            var page = (WorkdayFetchResult.SearchSuccess) search;
            assertThat(page.reportedTotal()).isNotNull().isNotNegative();
            assertThat(page.postings()).hasSizeLessThanOrEqualTo(WorkdayPageRequest.MAX_LIMIT);
            if (page.postings().isEmpty()) {
                assertThat(page.reportedTotal()).isZero();
                evidence(source, page.reportedTotal(), 0, 0, true);
                return;
            }

            var first = detail(gateway, source, page.postings().getFirst());
            var repeated = detail(gateway, source, page.postings().getFirst());
            assertThat(repeated.opaqueId()).isEqualTo(first.opaqueId());
            assertThat(repeated.jobPostingId()).isEqualTo(first.jobPostingId());
            assertThat(repeated.jobReqId()).isEqualTo(first.jobReqId());
            assertThat(repeated.title()).isEqualTo(first.title());
            assertThat(repeated.location()).isEqualTo(first.location());

            int secondPageSize = 0;
            if (page.reportedTotal() > WorkdayPageRequest.MAX_LIMIT) {
                WorkdayFetchResult next = gateway.searchPage(source,
                        new WorkdayPageRequest(WorkdayPageRequest.MAX_LIMIT, WorkdayPageRequest.MAX_LIMIT));
                assertThat(next).isInstanceOf(WorkdayFetchResult.SearchSuccess.class);
                List<WorkdaySearchPosting> postings = ((WorkdayFetchResult.SearchSuccess) next).postings();
                assertThat(postings).isNotEmpty().hasSizeLessThanOrEqualTo(WorkdayPageRequest.MAX_LIMIT);
                detail(gateway, source, postings.getFirst());
                secondPageSize = postings.size();
            }
            evidence(source, page.reportedTotal(), page.postings().size(), secondPageSize, true);
        } finally {
            client.dispatcher().executorService().shutdownNow(); client.connectionPool().evictAll();
        }
    }

    private static WorkdayJobDetail detail(WorkdayPostingGateway gateway, WorkdaySource source,
            WorkdaySearchPosting posting) {
        WorkdayFetchResult fetched = gateway.fetchDetail(source,
                new WorkdayDetailRequest(posting.externalPath()));
        assertThat(fetched).isInstanceOf(WorkdayFetchResult.DetailSuccess.class);
        WorkdayJobDetail job = ((WorkdayFetchResult.DetailSuccess) fetched).detail();
        assertThat(job.opaqueId()).isNotBlank().hasSizeLessThanOrEqualTo(255);
        assertThat(job.title()).isEqualTo(posting.title()).isNotBlank().hasSizeLessThanOrEqualTo(255);
        assertThat(job.descriptionHtml()).isNotBlank().hasSizeLessThanOrEqualTo(500_000);
        assertThat(job.posted()).isTrue();
        assertThat(job.canApply()).isTrue();
        assertThat(job.externalUrl().getScheme()).isEqualTo("https");
        assertThat(job.externalUrl().getHost()).isEqualTo(source.endpointHost());
        assertThat(job.externalUrl().getRawUserInfo()).isNull();
        assertThat(job.externalUrl().getPort()).isEqualTo(-1);
        assertThat(job.externalUrl().getRawFragment()).isNull();
        return job;
    }

    private static void evidence(WorkdaySource source, int total, int firstPage, int secondPage,
            boolean repeatedIdentityStable) {
        System.out.printf("WORKDAY_LIVE_EVIDENCE host=%s site=%s locale=%s total=%d firstPage=%d "
                        + "secondPage=%d repeatedIdentityStable=%s%n",
                source.endpointHost(), source.siteKey(), source.locale(), total, firstPage, secondPage,
                repeatedIdentityStable);
    }

    private static String required(String suffix) {
        String value = System.getProperty("jobcopilot.workday.live." + suffix);
        if (value == null || value.isBlank())
            throw new IllegalStateException("Explicit -Djobcopilot.workday.live." + suffix + " is required");
        return value;
    }
}
