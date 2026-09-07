package com.jobcopilot.matching;

import com.jobcopilot.common.text.MatchingVocabulary;
import com.jobcopilot.job.JobService;
import com.jobcopilot.job.JobStatus;
import com.jobcopilot.job.dto.CreateJobRequest;
import com.jobcopilot.job.dto.JobRequirementsRequest;
import com.jobcopilot.job.dto.UpdateJobStatusRequest;
import com.jobcopilot.matching.dto.MatchRequest;
import com.jobcopilot.matching.dto.MatchResponse;
import com.jobcopilot.resume.DeterministicProfileParser;
import com.jobcopilot.resume.ResumePersistenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class JobRankingIntegrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired JobRankingService rankingService;
    @Autowired MatchService matchService;
    @Autowired JobService jobService;
    @Autowired ResumePersistenceService resumes;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManagerFactory entityManagerFactory;

    @BeforeEach
    void clear() {
        jdbc.execute("DELETE FROM candidate_profiles");
        jdbc.execute("DELETE FROM resumes");
        jdbc.execute("DELETE FROM job_skills");
        jdbc.execute("DELETE FROM jobs");
    }

    @Test
    void ranksPersistedJobsWithoutWritingRankingStateAndMatchesSingleJobApi() {
        String text = """
                Experience
                Backend Engineer at Acme
                2020-01 - 2023-01
                - Java Spring Boot services
                Skills
                Java, Spring Boot, PostgreSQL
                """;
        var data = new DeterministicProfileParser().parse(text, LocalDate.of(2026, 9, 7));
        var saved = resumes.save(
                "resume.pdf",
                100,
                text,
                1,
                "test",
                data,
                LocalDate.of(2026, 9, 7),
                DeterministicProfileParser.VERSION,
                MatchingVocabulary.standard().version()
        );
        Long profileId = saved.candidateProfile().id();
        var profileBefore = resumes.getProfile(profileId);

        var strong = jobService.createJob(request("Backend Engineer", "Acme", "strong"));
        var weaker = jobService.createJob(request("Platform Engineer", "Acme", "weaker"));
        var preferredOnly = jobService.createJob(request("Preferred Engineer", "Acme", "preferred"));
        var unassessed = jobService.createJob(request("Blank Role", "Acme", "blank"));
        var rejected = jobService.createJob(request("Rejected Backend", "Acme", "rejected"));
        jobService.replaceRequirements(strong.id(), new JobRequirementsRequest(List.of("Java"), List.of(), null));
        jobService.replaceRequirements(weaker.id(), new JobRequirementsRequest(List.of("Java", "Redis"), List.of(), null));
        jobService.replaceRequirements(preferredOnly.id(), new JobRequirementsRequest(List.of(), List.of("Java"), null));
        jobService.replaceRequirements(rejected.id(), new JobRequirementsRequest(List.of("Java"), List.of(), null));
        jobService.updateJobStatus(rejected.id(), new UpdateJobStatusRequest(JobStatus.REJECTED));
        var strongAfterRequirements = jobService.getJob(strong.id());
        var rejectedAfterStatus = jobService.getJob(rejected.id());

        int tableCountBefore = publicTableCount();
        var ranking = rankingService.rank(profileId);
        MatchResponse single = matchService.match(new MatchRequest(profileId, strong.id()));

        assertEquals(5, ranking.evaluatedJobCount());
        assertEquals(4, ranking.rankedJobCount());
        assertEquals(1, ranking.unassessedJobCount());
        assertEquals(profileId, ranking.candidateProfileId());
        assertEquals(MatchingPolicy.v1().version(), ranking.algorithmVersion());
        assertEquals(MatchingVocabulary.standard().version(), ranking.vocabularyVersion());
        assertEquals(DeterministicProfileParser.VERSION, ranking.profileParserVersion());
        assertEquals(profileBefore.assessedOn(), ranking.profileAssessedOn());
        assertEquals(List.of(1, 2, 3, 4), ranking.rankedJobs().stream().map(item -> item.rank()).toList());
        assertTrue(ranking.rankedJobs().stream().anyMatch(item -> item.job().id().equals(rejected.id()) && item.job().status() == JobStatus.REJECTED));
        assertEquals(unassessed.id(), ranking.unassessedJobs().getFirst().job().id());
        assertEquals(com.jobcopilot.matching.dto.UnassessedJobResponse.Reason.INSUFFICIENT_JOB_REQUIREMENTS, ranking.unassessedJobs().getFirst().reason());

        var strongRanked = ranking.rankedJobs().stream().filter(item -> item.job().id().equals(strong.id())).findFirst().orElseThrow();
        assertEquals(single.overallScore(), strongRanked.match().overallScore());
        assertEquals(single.recommendation(), strongRanked.match().recommendation());
        assertEquals(single.matchedRequiredSkills(), strongRanked.match().matchedRequiredSkills());
        assertEquals(single.missingRequiredSkills(), strongRanked.match().missingRequiredSkills());
        assertEquals(single.appliedCaps(), strongRanked.match().appliedCaps());
        assertEquals(single.strengths(), strongRanked.match().strengths());
        assertEquals(single.gaps(), strongRanked.match().gaps());
        assertEquals(single.warnings(), strongRanked.match().warnings());
        assertEquals(single.unassessedFactors(), strongRanked.match().unassessedFactors());
        assertTrue(strongRanked.match().overallScore().compareTo(
                ranking.rankedJobs().stream().filter(item -> item.job().id().equals(weaker.id())).findFirst().orElseThrow().match().overallScore()) > 0);

        assertEquals(tableCountBefore, publicTableCount());
        assertEquals("3", jdbc.queryForObject("select max(version) from flyway_schema_history", String.class));
        assertEquals(0, jdbc.queryForObject(
                "select count(*) from information_schema.tables where table_schema = 'public' and table_name in ('job_rankings','match_results','matches')",
                Integer.class));
        assertEquals(profileBefore, resumes.getProfile(profileId));
        assertEquals(strongAfterRequirements, jobService.getJob(strong.id()));
        assertEquals(rejectedAfterStatus, jobService.getJob(rejected.id()));
        assertEquals(JobStatus.REJECTED, jobService.getJob(rejected.id()).status());
        assertFalse(ranking.rankedJobs().isEmpty());
        ranking.rankedJobs().forEach(item -> assertEquals(jobService.getJob(item.job().id()).updatedAt(), item.job().updatedAt()));
    }

    @Test
    void emptyStoreReturnsEmptyRanking() {
        String text = "Java";
        var data = new DeterministicProfileParser().parse(text, LocalDate.of(2026, 9, 7));
        var saved = resumes.save("resume.pdf", 10, text, 1, "test", data, LocalDate.of(2026, 9, 7),
                DeterministicProfileParser.VERSION, MatchingVocabulary.standard().version());

        var empty = rankingService.rank(saved.candidateProfile().id());
        assertEquals(0, empty.evaluatedJobCount());
        assertTrue(empty.rankedJobs().isEmpty());
        assertTrue(empty.unassessedJobs().isEmpty());
    }

    @Test
    void ranksDozensOfJobsAcrossEveryStatusWithConstantReadQueryCount() {
        String text = "Java backend engineer with PostgreSQL and five years experience";
        var data = new DeterministicProfileParser().parse(text, LocalDate.of(2026, 9, 7));
        var saved = resumes.save("resume.pdf", 64, text, 1, "test", data, LocalDate.of(2026, 9, 7),
                DeterministicProfileParser.VERSION, MatchingVocabulary.standard().version());

        JobStatus[] statuses = JobStatus.values();
        for (int index = 0; index < 42; index++) {
            var job = jobService.createJob(request("Backend Engineer " + index, "Company " + index, "smoke-" + index));
            jobService.replaceRequirements(job.id(), new JobRequirementsRequest(
                    List.of(index % 2 == 0 ? "Java" : "Redis"),
                    List.of(index % 3 == 0 ? "PostgreSQL" : "Docker"),
                    index % 5 == 0 ? new java.math.BigDecimal("3") : null));
            jobService.updateJobStatus(job.id(), new UpdateJobStatusRequest(statuses[index % statuses.length]));
        }

        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        try {
            var ranking = rankingService.rank(saved.candidateProfile().id());

            assertEquals(42, ranking.evaluatedJobCount());
            assertEquals(42, ranking.rankedJobCount());
            assertEquals(0, ranking.unassessedJobCount());
            assertEquals(3, statistics.getPrepareStatementCount(),
                    "candidate, resume text, and all jobs with skills should be the only reads");
            assertEquals(Arrays.stream(statuses).collect(Collectors.toSet()),
                    ranking.rankedJobs().stream().map(item -> item.job().status()).collect(Collectors.toSet()));
            assertEquals(Set.copyOf(ranking.rankedJobs().stream().map(item -> item.job().id()).toList()).size(),
                    ranking.rankedJobs().size(), "the collection fetch must not duplicate root jobs");
            assertEquals(java.util.stream.IntStream.rangeClosed(1, 42).boxed().toList(),
                    ranking.rankedJobs().stream().map(item -> item.rank()).toList());
        } finally {
            statistics.setStatisticsEnabled(false);
        }
    }

    private static CreateJobRequest request(String title, String company, String externalJobId) {
        return new CreateJobRequest(title, company, "Remote", "https://example.com/jobs/" + externalJobId,
                "Java backend payments systems", "TESTCONTAINERS", externalJobId);
    }

    private int publicTableCount() {
        return jdbc.queryForObject("select count(*) from information_schema.tables where table_schema = 'public'", Integer.class);
    }
}
