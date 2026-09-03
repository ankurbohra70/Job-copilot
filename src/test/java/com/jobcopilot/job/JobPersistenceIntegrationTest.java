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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class JobPersistenceIntegrationTest {

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("postgres:17.11-alpine")
    );

    @DynamicPropertySource
    static void configurePostgreSql(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    }

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private JobService jobService;

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

    private JobPageResponse search(String searchTerm, JobStatus status) {
        return jobService.getJobs(0, 20, "id,asc", searchTerm, status);
    }

    private static List<Long> ids(JobPageResponse page) {
        return page.content().stream().map(JobResponse::id).toList();
    }

    private static CreateJobRequest request(String title, String company, String externalJobId) {
        return new CreateJobRequest(
                title,
                company,
                "Remote",
                "https://example.com/jobs/" + externalJobId,
                "Integration test record",
                "TESTCONTAINERS",
                externalJobId
        );
    }
}
