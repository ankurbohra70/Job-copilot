package com.jobcopilot.matching;

import com.jobcopilot.common.text.MatchingVocabulary;
import com.jobcopilot.job.JobService;
import com.jobcopilot.job.JobStatus;
import com.jobcopilot.job.dto.CreateJobRequest;
import com.jobcopilot.job.dto.JobPageResponse;
import com.jobcopilot.job.dto.JobRequirementsRequest;
import com.jobcopilot.job.dto.UpdateJobStatusRequest;
import com.jobcopilot.common.web.ApiErrorHandler;
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
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
    @Autowired JobAssessmentService assessmentService;
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
        assertEquals("7", jdbc.queryForObject("select max(version) from flyway_schema_history", String.class));
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
    void extractingStoredDescriptionMakesAnUnassessedJobComputableForMatchingAndRanking() {
        Long profileId = saveProfile("""
                Experience
                Backend Engineer at Acme
                2020-01 - 2023-01
                - Java Spring Boot services
                Skills
                Java, Spring Boot
                """);
        var created = jobService.createJob(new CreateJobRequest(
                "Backend Engineer",
                "Acme",
                "Remote",
                "https://example.com/jobs/extract-rank",
                """
                Requirements:
                Java
                Redis
                Minimum 1 year of experience
                """,
                "TESTCONTAINERS",
                "extract-rank"
        ));

        var before = rank(profileId);
        assertEquals(List.of(created.id()), before.unassessedJobs().stream().map(item -> item.job().id()).toList());
        assertEquals(0, before.computableJobCount());
        assertThrows(MatchCannotBeComputedException.class,
                () -> matchService.match(new MatchRequest(profileId, created.id())));

        var extracted = jobService.extractRequirements(created.id());
        assertEquals(List.of("java", "redis"), extracted.requiredSkills());
        assertEquals(0, extracted.minYearsExperience().compareTo(new BigDecimal("1")));

        MatchResponse single = matchService.match(new MatchRequest(profileId, created.id()));
        var after = rank(profileId);
        var ranked = after.rankedJobs().stream().filter(item -> item.job().id().equals(created.id())).findFirst().orElseThrow();

        assertEquals(1, after.computableJobCount());
        assertEquals(0, after.unassessedJobCount());
        assertEquals(single.overallScore(), ranked.match().overallScore());
        assertEquals(single.recommendation(), ranked.match().recommendation());
        assertEquals(single.matchedRequiredSkills(), ranked.match().matchedRequiredSkills());
        assertCountInvariants(after);
    }

    @Test
    void extractedSpringBootMatchesManualSpringBootForMatchingAndRanking() {
        Long profileId = saveProfile("""
                Experience
                Backend Engineer at Acme
                2020-01 - 2023-01
                - Spring Boot services
                Skills
                Spring Boot
                """);
        String description = "Required: Spring Boot";
        var manual = jobService.createJob(new CreateJobRequest(
                "Manual Spring", "Acme", "Remote", "https://example.com/jobs/spring-manual",
                description, "TESTCONTAINERS", "spring-manual"));
        var extractedJob = jobService.createJob(new CreateJobRequest(
                "Extracted Spring", "Acme", "Remote", "https://example.com/jobs/spring-extract",
                description, "TESTCONTAINERS", "spring-extract"));
        jobService.replaceRequirements(manual.id(), new JobRequirementsRequest(List.of("spring-boot"), List.of(), null));
        var extracted = jobService.extractRequirements(extractedJob.id());

        assertEquals(List.of("spring-boot"), extracted.requiredSkills());
        assertEquals(List.of(), extracted.preferredSkills());
        assertEquals(jobService.getRequirements(manual.id()).requiredSkills(), extracted.requiredSkills());
        assertEquals(jobService.getRequirements(manual.id()).preferredSkills(), extracted.preferredSkills());

        MatchResponse manualMatch = matchService.match(new MatchRequest(profileId, manual.id()));
        MatchResponse extractedMatch = matchService.match(new MatchRequest(profileId, extractedJob.id()));
        assertEquals(manualMatch.overallScore(), extractedMatch.overallScore());
        assertEquals(manualMatch.recommendation(), extractedMatch.recommendation());
        assertEquals(manualMatch.matchedRequiredSkills(), extractedMatch.matchedRequiredSkills());
        assertEquals(manualMatch.missingRequiredSkills(), extractedMatch.missingRequiredSkills());

        var ranking = rank(profileId);
        var manualRanked = ranking.rankedJobs().stream().filter(item -> item.job().id().equals(manual.id())).findFirst().orElseThrow();
        var extractedRanked = ranking.rankedJobs().stream().filter(item -> item.job().id().equals(extractedJob.id())).findFirst().orElseThrow();
        assertEquals(manualRanked.match().overallScore(), extractedRanked.match().overallScore());
        assertEquals(manualRanked.match().recommendation(), extractedRanked.match().recommendation());
        assertCountInvariants(ranking);
    }

    @Test
    void springBootCandidateDoesNotSatisfySpringFrameworkRequirementInRanking() {
        Long profileId = saveProfile("""
                Experience
                Backend Engineer at Acme
                2020-01 - 2023-01
                - Spring Boot services
                Skills
                Spring Boot
                """);
        Long springJob = job(profileId, "Spring Framework Role", "spring-fw", JobStatus.DISCOVERED,
                List.of("spring"), List.of(), null);
        Long bootJob = job(profileId, "Spring Boot Role", "spring-boot-role", JobStatus.DISCOVERED,
                List.of("spring-boot"), List.of(), null);

        MatchResponse springMatch = matchService.match(new MatchRequest(profileId, springJob));
        MatchResponse bootMatch = matchService.match(new MatchRequest(profileId, bootJob));
        assertEquals(List.of("spring"), springMatch.missingRequiredSkills());
        assertTrue(springMatch.matchedRequiredSkills().isEmpty());
        assertEquals(List.of("spring-boot"), bootMatch.matchedRequiredSkills());

        var ranking = rank(profileId);
        var springRanked = ranking.rankedJobs().stream().filter(item -> item.job().id().equals(springJob)).findFirst().orElseThrow();
        var bootRanked = ranking.rankedJobs().stream().filter(item -> item.job().id().equals(bootJob)).findFirst().orElseThrow();
        assertEquals(springMatch.overallScore(), springRanked.match().overallScore());
        assertEquals(bootMatch.overallScore(), bootRanked.match().overallScore());
        assertTrue(bootMatch.overallScore().compareTo(springMatch.overallScore()) > 0);
        assertCountInvariants(ranking);
    }

    @Test
    void manualAndExtractedExperienceProduceTheSameMatchAndRanking() {
        Long profileId = saveProfile("""
                Experience
                Backend Engineer at Acme
                2020-01 - 2023-01
                - Java services
                Skills
                Java
                """);
        String description = """
                Requirements:
                Java
                2 years of experience
                """;
        var extractedJob = jobService.createJob(new CreateJobRequest(
                "Extracted Exp", "Acme", "Remote", "https://example.com/jobs/exp-extract",
                description, "TESTCONTAINERS", "exp-extract"));
        var manualJob = jobService.createJob(new CreateJobRequest(
                "Manual Exp", "Acme", "Remote", "https://example.com/jobs/exp-manual",
                description, "TESTCONTAINERS", "exp-manual"));
        var extracted = jobService.extractRequirements(extractedJob.id());
        var manual = jobService.replaceRequirements(manualJob.id(), new JobRequirementsRequest(
                List.of("java"), List.of(), new BigDecimal("2")));
        assertEquals(manual, extracted);

        MatchResponse extractedMatch = matchService.match(new MatchRequest(profileId, extractedJob.id()));
        MatchResponse manualMatch = matchService.match(new MatchRequest(profileId, manualJob.id()));
        assertEquals(manualMatch.overallScore(), extractedMatch.overallScore());
        assertEquals(manualMatch.recommendation(), extractedMatch.recommendation());
        assertEquals(manualMatch.experienceComparison().requiredYears(), extractedMatch.experienceComparison().requiredYears());

        var ranking = rank(profileId);
        var extractedRanked = ranking.rankedJobs().stream().filter(item -> item.job().id().equals(extractedJob.id())).findFirst().orElseThrow();
        var manualRanked = ranking.rankedJobs().stream().filter(item -> item.job().id().equals(manualJob.id())).findFirst().orElseThrow();
        assertEquals(manualRanked.match().overallScore(), extractedRanked.match().overallScore());
        assertEquals(manualRanked.match().recommendation(), extractedRanked.match().recommendation());
        assertCountInvariants(ranking);
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
    void assessmentSharesEngineScoresAndDoesNotChangeRanking() throws Exception {
        Long profileId = saveProfile("""
                Experience
                Backend Engineer at Acme
                2020-01 - 2023-01
                Skills
                Java
                """);
        Long computable = job(profileId, "Backend Engineer", "rank-assess", JobStatus.DISCOVERED, List.of("Java"), List.of(), null);
        Long unassessed = job(profileId, "Blank Role", "rank-blank", JobStatus.DISCOVERED, List.of(), List.of(), null);
        Long excluded = job(profileId, "Offer Role", "rank-offer", JobStatus.OFFER, List.of("Java"), List.of(), null);
        job(profileId, "Other Role", "rank-other", JobStatus.APPLIED, List.of("Redis"), List.of(), null);
        var queries = List.of(
                JobRankingQuery.parse(null, null, null, List.of("0"), List.of("1")),
                JobRankingQuery.parse(null, null, null, List.of("1"), List.of("1")),
                JobRankingQuery.parse(null, List.of("STRONG_MATCH"), List.of("80"), List.of("0"), List.of("1")),
                JobRankingQuery.parse(List.of("OFFER"), null, null, null, null));
        var queryResultsBefore = queries.stream().map(query -> rank(profileId, query)).toList();

        var before = rank(profileId);
        var validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new JobAssessmentController(assessmentService))
                .setControllerAdvice(new ApiErrorHandler())
                .setValidator(validator)
                .build();
        mvc.perform(post("/api/jobs/" + computable + "/assessment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"candidateProfileId\":" + profileId + "}"))
                .andExpect(status().isOk());
        validator.close();

        MatchResponse assessment = assessmentService.assess(computable, profileId);
        var after = rank(profileId);
        var ranked = after.rankedJobs().stream().filter(item -> item.job().id().equals(computable)).findFirst().orElseThrow();

        assertEquals(before, after);
        assertEquals(queryResultsBefore, queries.stream().map(query -> rank(profileId, query)).toList());
        assertEquals(assessment.overallScore(), ranked.match().overallScore());
        assertEquals(assessment.recommendation(), ranked.match().recommendation());
        assertEquals(assessment.matchedRequiredSkills(), ranked.match().matchedRequiredSkills());
        assertEquals(assessment.missingRequiredSkills(), ranked.match().missingRequiredSkills());
        assertEquals(assessment.matchedPreferredSkills(), ranked.match().matchedPreferredSkills());
        assertEquals(assessment.unmatchedPreferredSkills(), ranked.match().unmatchedPreferredSkills());
        assertEquals(assessment.experienceComparison(), ranked.match().experienceComparison());
        assertEquals(assessment.appliedCaps(), ranked.match().appliedCaps());
        assertEquals(assessment.strengths(), ranked.match().strengths());
        assertEquals(assessment.gaps(), ranked.match().gaps());
        assertEquals(assessment.warnings(), ranked.match().warnings());
        assertEquals(assessment.unassessedFactors(), ranked.match().unassessedFactors());
        assertEquals(List.of(unassessed), after.unassessedJobs().stream().map(item -> item.job().id()).toList());
        assertTrue(after.rankedJobs().stream().noneMatch(item -> item.job().id().equals(excluded)));
        assertCountInvariants(after);
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

    @Test void threeManualExtractionPairsHaveIdenticalMatchingAndRanking() {
        Long profileId = saveProfile("Experience\nBackend Engineer at Acme\n2020-01 - 2023-01\nSkills\nJava Spring Boot Redis Docker");
        String[] descriptions = {"Required: Spring Boot\nPreferred: Redis\nMinimum 2 years",
                "Required: Java and Redis\nPreferred: Docker", "Minimum 3 years"};
        var required = List.of(List.of("spring-boot"), List.of("java", "redis"), List.<String>of());
        var preferred = List.of(List.of("redis"), List.of("docker"), List.<String>of());
        for (int index = 0; index < descriptions.length; index++) {
            var a = jobService.createJob(new CreateJobRequest("Role", "Acme", null, null, descriptions[index], "TEST", "pair-manual-" + index));
            var b = jobService.createJob(new CreateJobRequest("Role", "Acme", null, null, descriptions[index], "TEST", "pair-extracted-" + index));
            BigDecimal minimum = index == 1 ? null : new BigDecimal(index == 0 ? "2.00" : "3.00");
            var manual = jobService.replaceRequirements(a.id(), new JobRequirementsRequest(required.get(index), preferred.get(index), minimum));
            assertEquals(manual, jobService.extractRequirements(b.id()));
            assertEquals(jobService.getRequirements(a.id()), jobService.getRequirements(b.id()));
            var manualMatch = matchService.match(new MatchRequest(profileId, a.id()));
            var extractedMatch = matchService.match(new MatchRequest(profileId, b.id()));
            assertEquals(manualMatch.overallScore(), extractedMatch.overallScore());
            assertEquals(manualMatch.recommendation(), extractedMatch.recommendation());
            var ranking = rank(profileId);
            var ra = ranking.rankedJobs().stream().filter(item -> item.job().id().equals(a.id())).findFirst().orElseThrow();
            var rb = ranking.rankedJobs().stream().filter(item -> item.job().id().equals(b.id())).findFirst().orElseThrow();
            assertEquals(ra.match(), rb.match());
            assertEquals(manualMatch.overallScore(), ra.match().overallScore());
            assertTrue(rb.rank() < ra.rank(), "equal scores retain the existing newer-job-first tie break");
            assertCountInvariants(ranking);
            System.out.println("Manual/extracted pair " + index + ": " + manualMatch.overallScore() + " " + manualMatch.recommendation());
        }
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
