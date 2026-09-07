package com.jobcopilot.matching;

import com.jobcopilot.common.text.MatchingVocabulary;
import com.jobcopilot.job.JobService;
import com.jobcopilot.job.JobStatus;
import com.jobcopilot.job.dto.CreateJobRequest;
import com.jobcopilot.job.dto.JobPageResponse;
import com.jobcopilot.job.dto.JobRequirementsRequest;
import com.jobcopilot.job.dto.UpdateJobStatusRequest;
import com.jobcopilot.matching.dto.JobRankingResponse;
import com.jobcopilot.matching.dto.MatchRequest;
import com.jobcopilot.matching.dto.MatchResponse;
import com.jobcopilot.resume.DeterministicProfileParser;
import com.jobcopilot.resume.ResumeExceptions.CandidateProfileNotFoundException;
import com.jobcopilot.resume.ResumePersistenceService;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    void defaultRankingExcludesTerminalStatusesPreservesParityAndIsReadOnly() {
        Long profileId = saveProfile("""
                Experience
                Backend Engineer at Acme
                2020-01 - 2023-01
                - Java Spring Boot services
                Skills
                Java, Spring Boot, PostgreSQL
                """);
        var profileBefore = resumes.getProfile(profileId);

        var strong = job(profileId, "Backend Engineer", "strong", JobStatus.DISCOVERED, List.of("Java"), List.of(), null);
        var weaker = job(profileId, "Platform Engineer", "weaker", JobStatus.SHORTLISTED, List.of("Java", "Redis"), List.of(), null);
        var preferredOnly = job(profileId, "Preferred Engineer", "preferred", JobStatus.APPLIED, List.of(), List.of("Java"), null);
        var interviewing = job(profileId, "Interview Engineer", "interview", JobStatus.INTERVIEWING, List.of("Java"), List.of(), null);
        var unassessed = job(profileId, "Blank Role", "blank", JobStatus.DISCOVERED, List.of(), List.of(), null);
        var offer = job(profileId, "Offer Backend", "offer", JobStatus.OFFER, List.of("Java"), List.of(), null);
        var rejected = job(profileId, "Rejected Backend", "rejected", JobStatus.REJECTED, List.of("Java"), List.of(), null);
        var withdrawn = job(profileId, "Withdrawn Backend", "withdrawn", JobStatus.WITHDRAWN, List.of("Java"), List.of(), null);
        var rejectedUnassessed = job(profileId, "Rejected Blank", "rejected-blank", JobStatus.REJECTED, List.of(), List.of(), null);
        var strongAfterRequirements = jobService.getJob(strong);
        var rejectedAfterStatus = jobService.getJob(rejected);

        int tableCountBefore = publicTableCount();
        var ranking = rank(profileId);
        MatchResponse single = matchService.match(new MatchRequest(profileId, strong));

        assertEquals(5, ranking.evaluatedJobCount());
        assertEquals(4, ranking.computableJobCount());
        assertEquals(1, ranking.unassessedJobCount());
        assertEquals(4, ranking.filteredJobCount());
        assertEquals(4, ranking.pageResultCount());
        assertEquals(Set.of(JobStatus.DISCOVERED, JobStatus.SHORTLISTED, JobStatus.APPLIED, JobStatus.INTERVIEWING),
                ranking.rankedJobs().stream().map(item -> item.job().status()).collect(Collectors.toSet()));
        assertTrue(ranking.rankedJobs().stream().map(item -> item.job().id()).toList()
                .containsAll(List.of(strong, weaker, preferredOnly, interviewing)));
        assertTrue(ranking.rankedJobs().stream().noneMatch(item -> Set.of(offer, rejected, withdrawn).contains(item.job().id())));
        assertEquals(List.of(unassessed), ranking.unassessedJobs().stream().map(item -> item.job().id()).toList());
        assertFalse(ranking.unassessedJobs().stream().anyMatch(item -> item.job().id().equals(rejectedUnassessed)));
        assertEquals(com.jobcopilot.matching.dto.UnassessedJobResponse.Reason.INSUFFICIENT_JOB_REQUIREMENTS,
                ranking.unassessedJobs().getFirst().reason());
        assertCountInvariants(ranking);

        var strongRanked = ranking.rankedJobs().stream().filter(item -> item.job().id().equals(strong)).findFirst().orElseThrow();
        assertEquals(single.overallScore(), strongRanked.match().overallScore());
        assertEquals(single.recommendation(), strongRanked.match().recommendation());
        assertEquals(single.matchedRequiredSkills(), strongRanked.match().matchedRequiredSkills());
        assertEquals(single.missingRequiredSkills(), strongRanked.match().missingRequiredSkills());
        assertEquals(single.matchedPreferredSkills(), strongRanked.match().matchedPreferredSkills());
        assertEquals(single.unmatchedPreferredSkills(), strongRanked.match().unmatchedPreferredSkills());
        assertEquals(single.experienceComparison(), strongRanked.match().experienceComparison());
        assertEquals(single.appliedCaps(), strongRanked.match().appliedCaps());
        assertEquals(single.strengths(), strongRanked.match().strengths());
        assertEquals(single.gaps(), strongRanked.match().gaps());
        assertEquals(single.warnings(), strongRanked.match().warnings());
        assertEquals(single.unassessedFactors(), strongRanked.match().unassessedFactors());
        assertTrue(strongRanked.match().overallScore().compareTo(
                ranking.rankedJobs().stream().filter(item -> item.job().id().equals(weaker)).findFirst().orElseThrow()
                        .match().overallScore()) > 0);

        assertEquals(tableCountBefore, publicTableCount());
        assertEquals("3", jdbc.queryForObject("select max(version) from flyway_schema_history", String.class));
        assertEquals(0, jdbc.queryForObject(
                "select count(*) from information_schema.tables where table_schema = 'public' and table_name in ('job_rankings','match_results','matches')",
                Integer.class));
        assertEquals(profileBefore, resumes.getProfile(profileId));
        assertEquals(strongAfterRequirements, jobService.getJob(strong));
        assertEquals(rejectedAfterStatus, jobService.getJob(rejected));
        assertEquals(JobStatus.REJECTED, jobService.getJob(rejected).status());
        ranking.rankedJobs().forEach(item -> assertEquals(jobService.getJob(item.job().id()).updatedAt(), item.job().updatedAt()));
    }

    @Test
    void explicitStatusOverrideAndUnassessedAreIndependentOfScoreFilters() {
        Long profileId = saveProfile("Java backend engineer");
        var discovered = job(profileId, "Discovered", "disc", JobStatus.DISCOVERED, List.of("Java"), List.of(), null);
        var offer = job(profileId, "Offer", "off", JobStatus.OFFER, List.of("Java"), List.of(), null);
        var unassessedDiscovered = job(profileId, "Gap Discovered", "gap-d", JobStatus.DISCOVERED, List.of(), List.of(), null);
        var unassessedOffer = job(profileId, "Gap Offer", "gap-o", JobStatus.OFFER, List.of(), List.of(), null);
        var rejected = job(profileId, "Rejected", "rej", JobStatus.REJECTED, List.of("Java"), List.of(), null);
        var unassessedRejected = job(profileId, "Gap Rejected", "gap-r", JobStatus.REJECTED, List.of(), List.of(), null);
        var withdrawn = job(profileId, "Withdrawn", "with", JobStatus.WITHDRAWN, List.of("Java"), List.of(), null);
        var unassessedWithdrawn = job(profileId, "Gap Withdrawn", "gap-w", JobStatus.WITHDRAWN, List.of(), List.of(), null);

        var offerOnly = rank(profileId, JobRankingQuery.parse(List.of("OFFER"), null, null, null, null));
        assertEquals(Set.of(offer, unassessedOffer), Set.copyOf(ids(offerOnly)));
        assertEquals(List.of(unassessedOffer), offerOnly.unassessedJobs().stream().map(item -> item.job().id()).toList());
        assertFalse(offerOnly.rankedJobs().stream().anyMatch(item -> item.job().id().equals(discovered)));
        assertCountInvariants(offerOnly);

        var rejectedOnly = rank(profileId, JobRankingQuery.parse(List.of("REJECTED"), null, null, null, null));
        assertEquals(Set.of(rejected, unassessedRejected), Set.copyOf(ids(rejectedOnly)));
        assertEquals(List.of(unassessedRejected), rejectedOnly.unassessedJobs().stream().map(item -> item.job().id()).toList());
        assertCountInvariants(rejectedOnly);

        var withdrawnOnly = rank(profileId, JobRankingQuery.parse(List.of("WITHDRAWN"), null, null, null, null));
        assertEquals(Set.of(withdrawn, unassessedWithdrawn), Set.copyOf(ids(withdrawnOnly)));
        assertEquals(List.of(unassessedWithdrawn), withdrawnOnly.unassessedJobs().stream().map(item -> item.job().id()).toList());
        assertCountInvariants(withdrawnOnly);

        var mixed = rank(profileId, JobRankingQuery.parse(List.of("DISCOVERED", "OFFER"), null, null, null, null));
        assertEquals(Set.of(discovered, offer, unassessedDiscovered, unassessedOffer), Set.copyOf(ids(mixed)));
        assertEquals(Set.of(unassessedOffer, unassessedDiscovered),
                mixed.unassessedJobs().stream().map(item -> item.job().id()).collect(Collectors.toSet()));
        assertCountInvariants(mixed);

        var filtered = rank(profileId, JobRankingQuery.parse(
                List.of("DISCOVERED", "OFFER"), List.of("STRONG_MATCH"), List.of("100"), null, null));
        assertEquals(0, filtered.filteredJobCount());
        assertEquals(Set.of(unassessedOffer, unassessedDiscovered),
                filtered.unassessedJobs().stream().map(item -> item.job().id()).collect(Collectors.toSet()));
        assertEquals(filtered.unassessedJobCount(), filtered.unassessedJobs().size());
        assertCountInvariants(filtered);
    }

    @Test
    void emptyStoreReturnsEmptyRanking() {
        Long profileId = saveProfile("Java");
        var empty = rank(profileId);
        assertEquals(0, empty.evaluatedJobCount());
        assertEquals(0, empty.totalPages());
        assertTrue(empty.rankedJobs().isEmpty());
        assertTrue(empty.unassessedJobs().isEmpty());
        assertCountInvariants(empty);
    }

    @Test
    void ranksStatusRestrictedJobsWithConstantReadQueryCountAndLeavesListingIntact() {
        Long profileId = saveProfile("Java backend engineer with PostgreSQL and five years experience");
        JobStatus[] statuses = JobStatus.values();
        job(profileId, "Backend Engineer 0", "smoke-0", JobStatus.DISCOVERED,
                List.of("Java"), List.of("PostgreSQL"), new BigDecimal("3"));

        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        try {
            var small = rank(profileId);
            assertEquals(1, small.evaluatedJobCount());
            assertEquals(3, statistics.getPrepareStatementCount(),
                    "one job should use candidate, resume, and one jobs-with-skills read");

            for (int index = 1; index < 42; index++) {
                job(profileId, "Backend Engineer " + index, "smoke-" + index, statuses[index % statuses.length],
                        List.of(index % 2 == 0 ? "Java" : "Redis"),
                        List.of(index % 3 == 0 ? "PostgreSQL" : "Docker"),
                        index % 5 == 0 ? new BigDecimal("3") : null);
            }

            statistics.clear();
            var ranking = rank(profileId, JobRankingQuery.parse(null, null, null, List.of("0"), List.of("100")));

            assertEquals(24, ranking.evaluatedJobCount());
            assertEquals(24, ranking.computableJobCount());
            assertEquals(0, ranking.unassessedJobCount());
            assertEquals(24, ranking.pageResultCount());
            assertEquals(1, ranking.totalPages());
            assertEquals(3, statistics.getPrepareStatementCount(),
                    "candidate, resume text, and status-eligible jobs with skills should be the only reads");
            assertEquals(0, statistics.getEntityInsertCount());
            assertEquals(0, statistics.getEntityUpdateCount());
            assertEquals(JobRankingQuery.DEFAULT_STATUSES,
                    ranking.rankedJobs().stream().map(item -> item.job().status()).collect(Collectors.toSet()));
            assertEquals(Set.copyOf(ranking.rankedJobs().stream().map(item -> item.job().id()).toList()).size(),
                    ranking.rankedJobs().size(), "the collection fetch must not duplicate root jobs");
            assertEquals(java.util.stream.IntStream.rangeClosed(1, 24).boxed().toList(),
                    ranking.rankedJobs().stream().map(item -> item.rank()).toList());
            assertCountInvariants(ranking);

            statistics.clear();
            var allStatuses = rank(profileId, JobRankingQuery.parse(
                    List.of("DISCOVERED", "SHORTLISTED", "APPLIED", "INTERVIEWING", "OFFER", "REJECTED", "WITHDRAWN"),
                    null, null, List.of("0"), List.of("100")));
            assertEquals(42, allStatuses.evaluatedJobCount());
            assertEquals(42, allStatuses.computableJobCount());
            assertEquals(3, statistics.getPrepareStatementCount(),
                    "all 42 jobs must still use candidate, resume, and one jobs-with-skills read");
        } finally {
            statistics.setStatisticsEnabled(false);
        }

        JobPageResponse listing = jobService.getJobs(0, 20, "id,asc", null, null);
        assertEquals(20, listing.content().size());
        assertEquals(42, listing.totalElements());
        assertEquals(3, listing.totalPages());
    }

    @Test
    void globalRankingIgnoresInsertOrderAndPaginatesContinuously() {
        Long profileId = saveProfile("Java");
        Long lowFirst = job(profileId, "Low", "low", JobStatus.DISCOVERED, List.of("Java", "Redis"), List.of(), null);
        Long highSecond = job(profileId, "High", "high", JobStatus.DISCOVERED, List.of("Java"), List.of(), null);
        Long midThird = job(profileId, "Mid", "mid", JobStatus.DISCOVERED, List.of("Java", "Docker"), List.of(), null);

        var page0 = rank(profileId, JobRankingQuery.parse(null, null, null, List.of("0"), List.of("2")));
        var page1 = rank(profileId, JobRankingQuery.parse(null, null, null, List.of("1"), List.of("2")));
        var beyond = rank(profileId, JobRankingQuery.parse(null, null, null, List.of("4"), List.of("2")));

        assertEquals(List.of(highSecond, midThird), page0.rankedJobs().stream().map(item -> item.job().id()).toList());
        assertEquals(List.of(1, 2), page0.rankedJobs().stream().map(item -> item.rank()).toList());
        assertEquals(List.of(lowFirst), page1.rankedJobs().stream().map(item -> item.job().id()).toList());
        assertEquals(List.of(3), page1.rankedJobs().stream().map(item -> item.rank()).toList());
        assertTrue(beyond.rankedJobs().isEmpty());
        assertEquals(0, beyond.pageResultCount());
        assertEquals(3, beyond.filteredJobCount());
        assertCountInvariants(page0);
        assertCountInvariants(beyond);
    }

    @Test
    void missingCandidateDoesNotQueryJobs() {
        job(saveProfile("Java"), "Role", "orphan-job", JobStatus.DISCOVERED, List.of("Java"), List.of(), null);
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        try {
            assertThrows(CandidateProfileNotFoundException.class, () -> rank(9_999_999L));
            assertEquals(1, statistics.getPrepareStatementCount());
        } finally {
            statistics.setStatisticsEnabled(false);
        }
    }

    private JobRankingResponse rank(Long profileId) {
        return rank(profileId, JobRankingQuery.parse(null, null, null, null, null));
    }

    private JobRankingResponse rank(Long profileId, JobRankingQuery query) {
        return rankingService.rank(profileId, query);
    }

    private Long saveProfile(String text) {
        var data = new DeterministicProfileParser().parse(text, LocalDate.of(2026, 9, 7));
        return resumes.save(
                "resume.pdf",
                Math.max(10, text.length()),
                text,
                1,
                "test",
                data,
                LocalDate.of(2026, 9, 7),
                DeterministicProfileParser.VERSION,
                MatchingVocabulary.standard().version()
        ).candidateProfile().id();
    }

    private Long job(
            Long ignoredProfileId,
            String title,
            String externalJobId,
            JobStatus status,
            List<String> required,
            List<String> preferred,
            BigDecimal minYears
    ) {
        var created = jobService.createJob(request(title, "Acme", externalJobId));
        jobService.replaceRequirements(created.id(), new JobRequirementsRequest(required, preferred, minYears));
        if (status != JobStatus.DISCOVERED) {
            jobService.updateJobStatus(created.id(), new UpdateJobStatusRequest(status));
        }
        return created.id();
    }

    private static List<Long> ids(JobRankingResponse ranking) {
        var ranked = ranking.rankedJobs().stream().map(item -> item.job().id()).collect(Collectors.toList());
        ranked.addAll(ranking.unassessedJobs().stream().map(item -> item.job().id()).toList());
        return ranked;
    }

    private static void assertCountInvariants(JobRankingResponse ranking) {
        assertEquals(ranking.computableJobCount() + ranking.unassessedJobCount(), ranking.evaluatedJobCount());
        assertTrue(ranking.filteredJobCount() <= ranking.computableJobCount());
        assertEquals(ranking.rankedJobs().size(), ranking.pageResultCount());
        assertEquals(ranking.unassessedJobs().size(), ranking.unassessedJobCount());
        assertTrue(ranking.pageResultCount() <= ranking.size());
    }

    private static CreateJobRequest request(String title, String company, String externalJobId) {
        return new CreateJobRequest(title, company, "Remote", "https://example.com/jobs/" + externalJobId,
                "Java backend payments systems", "TESTCONTAINERS", externalJobId);
    }

    private int publicTableCount() {
        return jdbc.queryForObject("select count(*) from information_schema.tables where table_schema = 'public'", Integer.class);
    }
}
