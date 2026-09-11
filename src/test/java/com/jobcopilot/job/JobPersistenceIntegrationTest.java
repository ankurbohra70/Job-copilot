package com.jobcopilot.job;

import com.jobcopilot.job.dto.CreateJobRequest;
import com.jobcopilot.job.dto.JobPageResponse;
import com.jobcopilot.job.dto.JobResponse;
import com.jobcopilot.job.dto.UpdateJobRequest;
import com.jobcopilot.job.dto.UpdateJobStatusRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class JobPersistenceIntegrationTest {
    @Test
    void requirementCollectionChangesTouchParentAndRoundTrip() {
        var created = jobService.createJob(request("Role", "Company", "requirements"));
        var initial = jobService.getJob(created.id());
        var requirements = new com.jobcopilot.job.dto.JobRequirementsRequest(List.of("Java"), List.of("Postgres"), null);
        jobService.replaceRequirements(created.id(), requirements);
        var updated = jobService.getJob(created.id());
        assertTrue(updated.updatedAt().isAfter(initial.updatedAt()));
        assertEquals(initial.createdAt(), updated.createdAt());
        assertEquals(List.of("java"), jobService.matchingSnapshot(created.id()).requirements().requiredSkills());
        jobService.replaceRequirements(created.id(), new com.jobcopilot.job.dto.JobRequirementsRequest(null,null,null));
        assertTrue(jobService.getRequirements(created.id()).requiredSkills().isEmpty());
        jobService.deleteJob(created.id());
    }

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("postgres:17.11-alpine")
    );

    @DynamicPropertySource
    static void configurePostgreSql(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private JobService jobService;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clearDatabase() {
        jobRepository.deleteAll();
    }

    @Test
    void persistsDefaultsEnumsUpdatesAndJpaLifecycleTimestamps() {
        JobResponse created = jobService.createJob(request(
                "Backend Engineer", "Example", "initial"
        ));

        assertNotNull(created.id());
        assertEquals(JobStatus.DISCOVERED, created.status());
        assertNotNull(created.createdAt());
        assertEquals(created.createdAt(), created.updatedAt());

        JobResponse persisted = jobService.getJob(created.id());
        assertNotNull(persisted.createdAt());
        assertEquals(persisted.createdAt(), persisted.updatedAt());
        LocalDateTime initialUpdatedAt = persisted.updatedAt();
        JobResponse updated = jobService.updateJob(created.id(), new UpdateJobRequest(
                "Senior Backend Engineer",
                "Updated Example",
                "Remote",
                "https://example.com/jobs/updated",
                "Updated description",
                "MANUAL",
                "updated"
        ));

        assertEquals(created.id(), updated.id());
        assertEquals(JobStatus.DISCOVERED, updated.status());
        assertEquals(persisted.createdAt(), updated.createdAt());
        assertTrue(updated.updatedAt().isAfter(initialUpdatedAt));

        JobResponse statusUpdated = jobService.updateJobStatus(
                created.id(), new UpdateJobStatusRequest(JobStatus.INTERVIEWING)
        );

        assertEquals(JobStatus.INTERVIEWING, statusUpdated.status());
        assertEquals(updated.title(), statusUpdated.title());
        assertEquals(updated.company(), statusUpdated.company());
        assertEquals(persisted.createdAt(), statusUpdated.createdAt());
        assertTrue(statusUpdated.updatedAt().isAfter(updated.updatedAt()));
        assertEquals(JobStatus.INTERVIEWING, jobService.getJob(created.id()).status());
    }

    @Test
    void searchesTitleCompanyAndLiteralLikeCharactersWithStatusAndSemantics() {
        JobResponse special = jobService.createJob(request(
                "Senior 100%_Java\\Engineer",
                "ACME_Path%Labs\\HQ",
                "special"
        ));
        jobService.updateJobStatus(special.id(), new UpdateJobStatusRequest(JobStatus.SHORTLISTED));
        jobService.createJob(request("Ordinary Developer", "ACME Consulting", "ordinary"));
        jobService.createJob(request("Percent-free role", "Plain Company", "plain"));

        assertEquals(List.of(special.id()), ids(search("senior", null)));
        assertEquals(List.of(special.id()), ids(search("LaBs", null)));
        assertEquals(List.of(special.id()), ids(search("%_java\\", null)));
        assertEquals(List.of(special.id()), ids(search("%", null)));
        assertEquals(List.of(special.id()), ids(search("_", null)));
        assertEquals(List.of(special.id()), ids(search("\\", null)));
        assertEquals(List.of(special.id()), ids(search("acme", JobStatus.SHORTLISTED)));
        assertTrue(search("ordinary", JobStatus.SHORTLISTED).content().isEmpty());
    }

    @Test
    void duplicatePrimarySortValuesHaveStablePageBoundaries() {
        List<Long> createdIds = List.of(
                jobService.createJob(request("Role A", "Duplicate Company", "a")).id(),
                jobService.createJob(request("Role B", "Duplicate Company", "b")).id(),
                jobService.createJob(request("Role C", "Duplicate Company", "c")).id(),
                jobService.createJob(request("Role D", "Duplicate Company", "d")).id()
        );

        JobPageResponse firstPage = jobService.getJobs(0, 2, "company,asc", null, null);
        JobPageResponse secondPage = jobService.getJobs(1, 2, "company,asc", null, null);

        assertEquals(createdIds.subList(0, 2), ids(firstPage));
        assertEquals(createdIds.subList(2, 4), ids(secondPage));
        assertFalse(ids(firstPage).stream().anyMatch(ids(secondPage)::contains));
        assertEquals(4, firstPage.totalElements());
        assertEquals(2, firstPage.totalPages());
    }

    @Test
    void rankingSnapshotsSurviveTransactionCloseAndUseOneBulkQuery() {
        JobResponse first = jobService.createJob(request("Java Engineer", "Acme", "rank-a"));
        JobResponse second = jobService.createJob(request("Python Engineer", "Acme", "rank-b"));
        JobResponse empty = jobService.createJob(request("Unspecified", "Acme", "rank-c"));
        jobService.replaceRequirements(first.id(), new com.jobcopilot.job.dto.JobRequirementsRequest(
                List.of("Java"), List.of("Docker"), new java.math.BigDecimal("2")));
        jobService.replaceRequirements(second.id(), new com.jobcopilot.job.dto.JobRequirementsRequest(
                List.of("Python"), List.of(), null));
        jobService.updateJobStatus(first.id(), new UpdateJobStatusRequest(JobStatus.REJECTED));

        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        List<JobRankingSnapshot> snapshots;
        try {
            snapshots = jobService.rankingSnapshots(Set.of(JobStatus.DISCOVERED, JobStatus.REJECTED));
            assertEquals(1, statistics.getPrepareStatementCount());
        } finally {
            statistics.setStatisticsEnabled(false);
        }

        assertEquals(3, snapshots.size());
        JobRankingSnapshot javaJob = snapshots.stream().filter(item -> item.matching().id().equals(first.id())).findFirst().orElseThrow();
        assertEquals("Acme", javaJob.company());
        assertEquals("https://example.com/jobs/rank-a", javaJob.jobUrl());
        assertEquals(JobStatus.REJECTED, javaJob.status());
        assertEquals(jobService.getJob(first.id()).createdAt(), javaJob.createdAt());
        assertEquals(jobService.matchingSnapshot(first.id()), javaJob.matching());
        assertEquals(List.of("java"), javaJob.matching().requirements().requiredSkills());
        assertEquals(List.of("docker"), javaJob.matching().requirements().preferredSkills());
        assertEquals(jobService.matchingSnapshot(empty.id()).requirements(), snapshots.stream()
                .filter(item -> item.matching().id().equals(empty.id())).findFirst().orElseThrow().matching().requirements());
        assertTrue(snapshots.stream().anyMatch(item -> item.matching().id().equals(second.id())));

        List<JobRankingSnapshot> rejectedOnly = jobService.rankingSnapshots(Set.of(JobStatus.REJECTED));
        assertEquals(List.of(first.id()), rejectedOnly.stream().map(item -> item.matching().id()).toList());
        assertTrue(jobService.rankingSnapshots(Set.of(JobStatus.OFFER)).isEmpty());
        JobPageResponse listing = jobService.getJobs(0, 2, "id,asc", null, null);
        assertEquals(2, listing.content().size());
        assertEquals(3, listing.totalElements());
    }

    @Test
    void extractionPersistsImportanceExperienceAndIsIdempotentUntilDescriptionChanges() {
        JobResponse created = jobService.createJob(requestWithDescription(
                "Extractor Role",
                "Acme",
                "extract-1",
                """
                Requirements:
                Java
                Redis
                2 years of experience

                Preferred:
                Docker
                """
        ));
        JobResponse before = jobService.getJob(created.id());
        var extracted = jobService.extractRequirements(created.id());

        assertEquals(List.of("java", "redis"), extracted.requiredSkills());
        assertEquals(List.of("docker"), extracted.preferredSkills());
        assertEquals(0, extracted.minYearsExperience().compareTo(new java.math.BigDecimal("2")));
        assertEquals(List.of(
                Map.of("skill", "docker", "importance", "PREFERRED"),
                Map.of("skill", "java", "importance", "REQUIRED"),
                Map.of("skill", "redis", "importance", "REQUIRED")
        ), jdbcTemplate.queryForList(
                "select skill, importance from job_skills where job_id = ? order by skill", created.id()));
        JobResponse afterExtract = jobService.getJob(created.id());
        assertTrue(afterExtract.updatedAt().isAfter(before.updatedAt()));
        assertEquals(before.createdAt(), afterExtract.createdAt());
        assertEquals(JobStatus.DISCOVERED, afterExtract.status());
        assertEquals(before.description(), afterExtract.description());
        assertEquals(before.title(), afterExtract.title());
        assertEquals(before.company(), afterExtract.company());

        var repeated = jobService.extractRequirements(created.id());
        JobResponse afterRepeat = jobService.getJob(created.id());
        assertEquals(extracted.requiredSkills(), repeated.requiredSkills());
        assertEquals(extracted.preferredSkills(), repeated.preferredSkills());
        assertEquals(0, extracted.minYearsExperience().compareTo(repeated.minYearsExperience()));
        assertEquals(afterExtract.updatedAt(), afterRepeat.updatedAt());
        assertEquals(afterExtract.createdAt(), afterRepeat.createdAt());

        JobResponse afterUnusableDescription = jobService.updateJob(created.id(), new UpdateJobRequest(
                created.title(),
                created.company(),
                created.location(),
                created.jobUrl(),
                "A friendly workplace with no technologies listed.",
                created.source(),
                created.externalJobId()
        ));
        Long jobId = afterUnusableDescription.id();
        assertThrows(JobRequirementExtractionException.class, () -> jobService.extractRequirements(jobId));
        assertEquals(extracted.requiredSkills(), jobService.getRequirements(jobId).requiredSkills());
        assertEquals(extracted.preferredSkills(), jobService.getRequirements(jobId).preferredSkills());
        assertEquals(0, extracted.minYearsExperience().compareTo(jobService.getRequirements(jobId).minYearsExperience()));

        jobService.updateJob(jobId, new UpdateJobRequest(
                afterUnusableDescription.title(),
                afterUnusableDescription.company(),
                afterUnusableDescription.location(),
                afterUnusableDescription.jobUrl(),
                """
                Nice to have:
                AWS
                """,
                afterUnusableDescription.source(),
                afterUnusableDescription.externalJobId()
        ));
        var replaced = jobService.extractRequirements(jobId);
        assertTrue(replaced.requiredSkills().isEmpty());
        assertEquals(List.of("aws"), replaced.preferredSkills());
        assertNull(replaced.minYearsExperience());
        assertEquals(List.of("aws"), jobService.getRequirements(jobId).preferredSkills());
        assertTrue(jobService.getJob(created.id()).updatedAt().isAfter(afterRepeat.updatedAt()));
    }

    @Test
    void manualAndExtractedExperienceAreStructurallyEquivalent() {
        JobResponse created = jobService.createJob(requestWithDescription(
                "Equiv Role",
                "Acme",
                "equiv-1",
                """
                Requirements:
                Java
                2 years of experience
                """
        ));
        var extracted = jobService.extractRequirements(created.id());
        JobResponse manualJob = jobService.createJob(request("Equiv Manual", "Acme", "equiv-2"));
        var manual = jobService.replaceRequirements(manualJob.id(), new com.jobcopilot.job.dto.JobRequirementsRequest(
                List.of("java"), List.of(), new java.math.BigDecimal("2")));
        assertEquals(new java.math.BigDecimal("2.00"), extracted.minYearsExperience());
        assertEquals(new java.math.BigDecimal("2.00"), manual.minYearsExperience());
        assertEquals(manual, extracted);
        assertEquals(manual, jobService.getRequirements(created.id()));
        assertEquals(extracted, jobService.getRequirements(manualJob.id()));
    }

    private JobPageResponse search(String searchTerm, JobStatus status) {
        return jobService.getJobs(0, 20, "id,asc", searchTerm, status);
    }

    @Test
    void invalidExtractionReturns422AndPreservesStoredRequirementsAndTimestamp() throws Exception {
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new JobController(jobService))
                .setControllerAdvice(new com.jobcopilot.common.web.ApiErrorHandler()).build();
        for (String number : List.of("80.001", "80.004", "-2", "100", "2.345")) {
            var created = jobService.createJob(requestWithDescription("Role", "Company", "invalid-" + number,
                    "Required: Java\nMinimum " + number + " years experience"));
            jobService.replaceRequirements(created.id(), new com.jobcopilot.job.dto.JobRequirementsRequest(
                    List.of("redis"), List.of("docker"), new java.math.BigDecimal("3.00")));
            var before = jobService.getRequirements(created.id());
            var updatedAt = jobService.getJob(created.id()).updatedAt();
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                    "/api/jobs/" + created.id() + "/requirements/extract"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnprocessableEntity());
            assertEquals(before, jobService.getRequirements(created.id()));
            assertEquals(updatedAt, jobService.getJob(created.id()).updatedAt());
            assertEquals(new java.math.BigDecimal("3.00"), jdbcTemplate.queryForObject(
                    "select min_years_experience from jobs where id=?", java.math.BigDecimal.class, created.id()));
            assertEquals(List.of(Map.of("skill", "docker", "importance", "PREFERRED"),
                    Map.of("skill", "redis", "importance", "REQUIRED")), jdbcTemplate.queryForList(
                    "select skill,importance from job_skills where job_id=? order by skill", created.id()));
        }
    }

    @Test void decimalManualAndExtractedNoOpsPreserveStoredTimestamp() {
        for (String number : List.of("2.00", "2.5")) {
            var created = jobService.createJob(requestWithDescription("Role", "Company", "decimal-" + number,
                    "Required: Java\nMinimum " + number + " years experience"));
            var manual = jobService.replaceRequirements(created.id(), new com.jobcopilot.job.dto.JobRequirementsRequest(
                    List.of("java"), List.of(), new java.math.BigDecimal(number)));
            var before = jobService.getJob(created.id());
            assertEquals(manual, jobService.extractRequirements(created.id()));
            assertEquals(manual, jobService.extractRequirements(created.id()));
            assertEquals(before.updatedAt(), jobService.getJob(created.id()).updatedAt());
            assertEquals(new java.math.BigDecimal(number).setScale(2), jdbcTemplate.queryForObject(
                    "select min_years_experience from jobs where id=?", java.math.BigDecimal.class, created.id()));
        }
    }

    private static List<Long> ids(JobPageResponse page) {
        return page.content().stream().map(JobResponse::id).toList();
    }

    private static CreateJobRequest request(String title, String company, String externalJobId) {
        return requestWithDescription(title, company, externalJobId, "Integration test record");
    }

    private static CreateJobRequest requestWithDescription(
            String title,
            String company,
            String externalJobId,
            String description
    ) {
        return new CreateJobRequest(
                title,
                company,
                "Remote",
                "https://example.com/jobs/" + externalJobId,
                description,
                "TESTCONTAINERS",
                externalJobId
        );
    }
}
