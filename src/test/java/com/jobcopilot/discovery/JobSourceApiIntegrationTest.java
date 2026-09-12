package com.jobcopilot.discovery;

import com.jobcopilot.common.web.ApiErrorHandler;
import com.jobcopilot.discovery.lever.LeverFetchResult;
import com.jobcopilot.discovery.lever.LeverPosting;
import com.jobcopilot.discovery.lever.LeverPostingGateway;
import com.jobcopilot.job.JobService;
import com.jobcopilot.job.JobController;
import com.jobcopilot.job.JobStatus;
import com.jobcopilot.job.dto.UpdateJobStatusRequest;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class JobSourceApiIntegrationTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.jpa.properties.hibernate.generate_statistics", () -> "true");
    }

    @MockitoBean LeverPostingGateway gateway;
    @Autowired JobSourceController controller;
    @Autowired JobController jobController;
    @Autowired JobSourceService service;
    @Autowired JobService jobs;
    @Autowired JobSourceRepository sources;
    @Autowired JobSourceSyncRunRepository runs;
    @Autowired DiscoverySyncTransactions transactions;
    @Autowired JdbcTemplate jdbc;
    @Autowired jakarta.persistence.EntityManagerFactory entityManagerFactory;

    private final JsonMapper json = JsonMapper.builder().build();
    private LocalValidatorFactoryBean validator;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE TABLE job_source_sync_runs, external_job_listings, job_skills, jobs, job_sources RESTART IDENTITY CASCADE");
        reset(gateway);
        validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mvc = MockMvcBuilders.standaloneSetup(controller, jobController).setControllerAdvice(new ApiErrorHandler())
                .setValidator(validator).build();
    }

    @Test
    void fullHttpFlowPreservesTransactionAvailabilityAndCanonicalJob() throws Exception {
        long sourceId = create(" Example ");
        mvc.perform(get("/api/job-sources/{id}", sourceId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceKey").value("example"));

        doAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            return new LeverFetchResult.Success(invocation.getArgument(0), invocation.getArgument(1),
                    List.of(posting("one", "Required: Java and SQL")), Duration.ZERO);
        }).when(gateway).fetchPage(any(), any());

        String syncBody = mvc.perform(post("/api/job-sources/{id}/sync", sourceId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andReturn().getResponse().getContentAsString();
        long runId = json.readTree(syncBody).get("runId").asLong();

        mvc.perform(get("/api/job-sources/{id}/sync-runs", sourceId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].runId").value(runId));

        String listingBody = mvc.perform(get("/api/job-sources/{id}/listings", sourceId)
                        .queryParam("availability", "LIVE"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].availability").value("LIVE"))
                .andExpect(jsonPath("$.content[0].jobStatus").value("DISCOVERED"))
                .andExpect(jsonPath("$.content[0].extractionState").value("CURRENT"))
                .andExpect(jsonPath("$.content[0].rankingReady").value(true))
                .andExpect(jsonPath("$.content[0].description").doesNotExist())
                .andExpect(jsonPath("$.content[0].providerContentDigest").doesNotExist())
                .andExpect(jsonPath("$.content[0].extractionFingerprint").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        long listingId = json.readTree(listingBody).get("content").get(0).get("listingId").asLong();
        long jobId = json.readTree(listingBody).get("content").get(0).get("jobId").asLong();
        mvc.perform(get("/api/job-sources/{id}/listings", sourceId).queryParam("jobId", Long.toString(jobId)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));

        mvc.perform(get("/api/jobs/{id}", jobId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISCOVERED"));
        mvc.perform(get("/api/jobs/{id}/requirements", jobId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.requiredSkills").isArray());

        jobs.updateJobStatus(jobId, new UpdateJobStatusRequest(JobStatus.APPLIED));
        respondWith();
        mvc.perform(post("/api/job-sources/{id}/sync", sourceId)).andExpect(status().isOk());
        mvc.perform(get("/api/job-sources/{sourceId}/listings/{listingId}", sourceId, listingId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.availability").value("CLOSED"))
                .andExpect(jsonPath("$.jobStatus").value("APPLIED"));
        assertEquals(JobStatus.APPLIED, jobs.getJob(jobId).status());
    }

    @Test
    void providerFailureIsAuditedAndReturnsHttp200() throws Exception {
        long sourceId = create("example");
        doAnswer(invocation -> new LeverFetchResult.Failure(invocation.getArgument(0), invocation.getArgument(1),
                LeverFetchResult.FailureKind.PROVIDER_UNAVAILABLE, 503, Duration.ZERO))
                .when(gateway).fetchPage(any(), any());

        String body = mvc.perform(post("/api/job-sources/{id}/sync", sourceId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureCode").value("PROVIDER_UNAVAILABLE"))
                .andReturn().getResponse().getContentAsString();
        long runId = json.readTree(body).get("runId").asLong();
        mvc.perform(get("/api/job-sources/{id}/sync-runs/{runId}", sourceId, runId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("FAILED"));
    }

    @Test
    void sourceScopedChildrenStayHiddenAndLegacyInvalidSourceConflicts() throws Exception {
        long first = create("first");
        respondWith(posting("one", "Required: Java"));
        String sync = mvc.perform(post("/api/job-sources/{id}/sync", first)).andReturn()
                .getResponse().getContentAsString();
        long runId = json.readTree(sync).get("runId").asLong();
        String listing = mvc.perform(get("/api/job-sources/{id}/listings", first)).andReturn()
                .getResponse().getContentAsString();
        long listingId = json.readTree(listing).get("content").get(0).get("listingId").asLong();
        long second = create("second");

        mvc.perform(get("/api/job-sources/{id}/sync-runs/{runId}", second, runId))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/job-sources/{id}/listings/{listingId}", second, listingId))
                .andExpect(status().isNotFound());

        jdbc.update("""
                INSERT INTO job_sources(provider, region, source_key, company_name, enabled, created_at, updated_at)
                VALUES ('LEVER', 'GLOBAL', '.', 'Legacy', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """);
        long legacyId = jdbc.queryForObject("SELECT id FROM job_sources WHERE source_key = '.'", Long.class);
        mvc.perform(post("/api/job-sources/{id}/sync", legacyId)).andExpect(status().isConflict());
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM job_source_sync_runs WHERE job_source_id = ?", Integer.class, legacyId));
    }

    @Test
    void listingPaginationIsStableNonDuplicatingAndBoundedWithMultipleSkills() throws Exception {
        long sourceId = create("example");
        respondWith(posting("one", "Required: Java, SQL, Spring Boot"),
                posting("two", "Required: Java and Docker"));
        mvc.perform(post("/api/job-sources/{id}/sync", sourceId)).andExpect(status().isOk());

        SessionFactory factory = entityManagerFactory.unwrap(SessionFactory.class);
        factory.getStatistics().clear();
        var page = service.listings(sourceId, 0, 1, "lastVerifiedAt,desc", null, null);

        assertEquals(1, page.content().size());
        assertEquals(2, page.totalElements());
        assertEquals(2, page.totalPages());
        assertTrue(factory.getStatistics().getQueryExecutionCount() <= 4,
                "listing reads must use a bounded query count");
    }

    @Test
    void sourceStatePagingDuplicateRaceAndRunConflictsFollowTheContract() throws Exception {
        long disabledId = create("disabled");
        mvc.perform(patch("/api/job-sources/{id}/enabled", disabledId)
                        .contentType("application/json").content("{\"enabled\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));
        mvc.perform(post("/api/job-sources/{id}/sync", disabledId)).andExpect(status().isConflict());
        mvc.perform(get("/api/job-sources").queryParam("enabled", "false"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get("/api/job-sources").queryParam("sort", "sourceKey,asc"))
                .andExpect(status().isBadRequest());

        var request = new com.jobcopilot.discovery.dto.CreateJobSourceRequest(
                JobSourceProvider.LEVER, com.jobcopilot.discovery.lever.LeverRegion.GLOBAL,
                "race", "Race", true);
        var executor = Executors.newFixedThreadPool(2);
        try {
            List<Callable<Object>> calls = List.of(
                    () -> attemptCreate(request), () -> attemptCreate(request));
            List<Object> outcomes = executor.invokeAll(calls).stream().map(future -> {
                try { return future.get(); } catch (Exception exception) { throw new RuntimeException(exception); }
            }).toList();
            assertEquals(1, outcomes.stream()
                    .filter(com.jobcopilot.discovery.dto.JobSourceResponse.class::isInstance).count());
            assertEquals(1, outcomes.stream().filter(JobSourceApiExceptions.DuplicateSource.class::isInstance).count());
            assertEquals(1, sources.findAll().stream().filter(source -> source.sourceKey().equals("race")).count());
        } finally {
            executor.shutdownNow();
        }

        JobSource enabled = sources.findByProviderAndRegionAndSourceKey(JobSourceProvider.LEVER,
                com.jobcopilot.discovery.lever.LeverRegion.GLOBAL, "race").orElseThrow();
        var started = transactions.start(enabled.id(), JobSourceSyncTrigger.MANUAL, java.time.LocalDateTime.now());
        mvc.perform(post("/api/job-sources/{id}/sync", enabled.id())).andExpect(status().isConflict());
        JobSourceSyncRun run = runs.findById(started.runId()).orElseThrow();
        run.fail(java.time.LocalDateTime.now().plusSeconds(1), "PRIVATE_INTERNAL_CODE", JobSourceSyncCounters.zero());
        runs.saveAndFlush(run);
        mvc.perform(get("/api/job-sources/{id}/sync-runs/{runId}", enabled.id(), run.id()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.failureCode").value("SYNC_FAILED"));
        mvc.perform(get("/api/job-sources/{id}/sync-runs", enabled.id()).queryParam("status", "FAILED"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
    }

    private Object attemptCreate(com.jobcopilot.discovery.dto.CreateJobSourceRequest request) {
        try {
            return service.create(request);
        } catch (JobSourceApiExceptions.DuplicateSource duplicate) {
            return duplicate;
        }
    }

    private long create(String sourceKey) throws Exception {
        String body = mvc.perform(post("/api/job-sources").contentType("application/json").content("""
                {"provider":"LEVER","region":"GLOBAL","sourceKey":"%s",
                 "companyName":"Example","enabled":true}
                """.formatted(sourceKey)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("id").asLong();
    }

    private void respondWith(LeverPosting... postings) {
        doAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            return new LeverFetchResult.Success(invocation.getArgument(0), invocation.getArgument(1),
                    List.of(postings), Duration.ZERO);
        }).when(gateway).fetchPage(any(), any());
    }

    private static LeverPosting posting(String id, String description) {
        return LeverPostingMapperTest.posting(id, "Engineer", LeverPostingMapperTest.categories(),
                new LeverPosting.Content(null, description, List.of(), null, null));
    }
}
