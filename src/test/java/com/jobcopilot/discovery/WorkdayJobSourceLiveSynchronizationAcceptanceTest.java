package com.jobcopilot.discovery;

import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfSystemProperty(named = "jobcopilot.workday.live.sync", matches = "true")
class WorkdayJobSourceLiveSynchronizationAcceptanceTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired JobSourceSyncDispatcher dispatcher;
    @Autowired JobSourceRepository sources;
    @Autowired ExternalJobListingRepository listings;
    @Autowired JdbcTemplate jdbc;

    @Test void completeLiveSynchronizationAndImmediateResynchronizationAreIdempotent() {
        jdbc.execute("TRUNCATE TABLE job_source_sync_runs, external_job_listings, job_skills, jobs, "
                + "job_sources RESTART IDENTITY CASCADE");
        JobSource source = sources.saveAndFlush(new JobSource(JobSourceProvider.WORKDAY, null, null,
                "Live acceptance", true, required("host"), required("tenant"), required("site"),
                required("locale")));

        JobSourceSyncResult first = dispatcher.synchronize(source.id(), JobSourceSyncTrigger.MANUAL);
        assertEquals(JobSourceSyncStatus.SUCCEEDED, first.status());
        assertNull(first.failureCode());
        assertTrue(first.counters().created() > 20, "acceptance source must exercise multiple pages");
        assertEquals(0, first.counters().closed());
        Map<String, Long> firstIds = listingIds(source);
        assertEquals(first.counters().created(), firstIds.size());

        JobSourceSyncResult second = dispatcher.synchronize(source.id(), JobSourceSyncTrigger.MANUAL);
        assertEquals(JobSourceSyncStatus.SUCCEEDED, second.status());
        assertNull(second.failureCode());
        assertEquals(0, second.counters().created());
        assertEquals(0, second.counters().updated());
        assertEquals(0, second.counters().closed());
        assertEquals(0, second.counters().reopened());
        assertEquals(firstIds.size(), second.counters().unchanged());
        assertEquals(firstIds, listingIds(source));
        assertNotNull(sources.findById(source.id()).orElseThrow().lastSuccessfulSyncAt());

        System.out.printf("WORKDAY_LIVE_SYNC_EVIDENCE host=%s site=%s locale=%s postings=%d "
                        + "secondUnchanged=%d identitySetStable=true%n",
                source.endpointHost(), source.siteKey(), source.locale(), firstIds.size(),
                second.counters().unchanged());
    }

    private Map<String, Long> listingIds(JobSource source) {
        return listings.findByJobSource(source).stream().collect(Collectors.toMap(
                ExternalJobListing::externalJobId, ExternalJobListing::id));
    }

    private static String required(String suffix) {
        String value = System.getProperty("jobcopilot.workday.live." + suffix);
        if (value == null || value.isBlank())
            throw new IllegalStateException("Explicit -Djobcopilot.workday.live." + suffix + " is required");
        return value;
    }
}
