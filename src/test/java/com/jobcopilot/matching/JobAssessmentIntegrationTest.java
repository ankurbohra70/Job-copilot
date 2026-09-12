package com.jobcopilot.matching;

import com.jobcopilot.common.text.MatchingVocabulary;
import com.jobcopilot.job.JobService;
import com.jobcopilot.job.JobStatus;
import com.jobcopilot.job.dto.CreateJobRequest;
import com.jobcopilot.job.dto.JobRequirementsRequest;
import com.jobcopilot.job.dto.JobResponse;
import com.jobcopilot.job.dto.UpdateJobRequest;
import com.jobcopilot.job.dto.UpdateJobStatusRequest;
import com.jobcopilot.matching.dto.MatchResponse;
import com.jobcopilot.resume.DeterministicProfileParser;
import com.jobcopilot.resume.ResumePersistenceService;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class JobAssessmentIntegrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired JobService jobService;
    @Autowired ResumePersistenceService resumes;
    @Autowired JobAssessmentService assessments;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManagerFactory entityManagerFactory;
    @MockitoSpyBean DeterministicMatchingEngine engine;
    @Value("${local.server.port}") int port;

    private final HttpClient client = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void clear() {
        reset(engine);
        jdbc.execute("DELETE FROM candidate_profiles");
        jdbc.execute("DELETE FROM resumes");
        jdbc.execute("DELETE FROM job_skills");
        jdbc.execute("DELETE FROM jobs");
    }

    @Test
    void endToEndAssessmentMatchesDirectEngineOracle() throws Exception {
        Long profileId = saveProfile("""
                Experience
                Backend Engineer at Acme
                2020-01 - 2023-01
                - Java Spring Boot services
                Skills
                Java, Spring Boot, PostgreSQL
                """);
        Long jobId = persistJob("Backend Engineer", "Java payments systems", List.of("Java"), List.of("PostgreSQL"), new BigDecimal("1"));

        var response = post("/api/jobs/" + jobId + "/assessment", "{\"candidateProfileId\":" + profileId + "}");
        assertEquals(200, response.statusCode(), response.body());
        MatchResponse actual = assessments.assess(jobId, profileId);
        MatchResponse expected = oracle(jobId, profileId);

        assertEquals(expected, actual);
        JsonNode body = json.readTree(response.body());
        assertEquals(profileId, body.path("candidateProfileId").asLong());
        assertEquals(jobId, body.path("jobId").asLong());
        assertEquals(0, expected.overallScore().compareTo(body.path("overallScore").decimalValue()));
        assertEquals(expected.recommendation().name(), body.path("recommendation").asText());
        assertFalse(body.path("matchedRequiredSkills").isEmpty());
        assertTrue(body.has("experienceComparison"));
        assertTrue(body.has("roleRelevance"));
        assertTrue(body.has("keywordRelevance"));
    }

    @Test
    void legacyMatchesEndpointIsEquivalent() throws Exception {
        Long profileId = saveProfile("Java backend engineer with PostgreSQL");
        Long jobId = persistJob("Backend Engineer", "Java services", List.of("Java"), List.of(), null);

        var assessment = post("/api/jobs/" + jobId + "/assessment", "{\"candidateProfileId\":" + profileId + "}");
        var matches = post("/api/matches", "{\"candidateProfileId\":" + profileId + ",\"jobId\":" + jobId + "}");
        assertEquals(200, assessment.statusCode(), assessment.body());
        assertEquals(200, matches.statusCode(), matches.body());
        assertEquals(json.readTree(assessment.body()), json.readTree(matches.body()));
        assertEquals(assessments.assess(jobId, profileId), oracle(jobId, profileId));
    }

    @Test
    void repeatedAssessmentIsReadOnly() throws Exception {
        Long profileId = saveProfile("Java backend engineer");
        Long jobId = persistJob("Backend Engineer", "Java", List.of("Java"), List.of("PostgreSQL"), new BigDecimal("2"));
        var jobBefore = jobService.getJob(jobId);
        var requirementsBefore = jobService.getRequirements(jobId);
        var profileBefore = resumes.getProfile(profileId);
        int tablesBefore = publicTableCount();
        var rowsBefore = sourceRows();

        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        try {
            var first = post("/api/jobs/" + jobId + "/assessment", "{\"candidateProfileId\":" + profileId + "}");
            var second = post("/api/jobs/" + jobId + "/assessment", "{\"candidateProfileId\":" + profileId + "}");
            assertEquals(200, first.statusCode(), first.body());
            assertEquals(json.readTree(first.body()), json.readTree(second.body()));
            assertEquals(0, statistics.getEntityInsertCount());
            assertEquals(0, statistics.getEntityUpdateCount());
            assertEquals(0, statistics.getEntityDeleteCount());
            assertEquals(0, statistics.getCollectionRecreateCount());
            assertEquals(0, statistics.getCollectionUpdateCount());
            assertEquals(0, statistics.getCollectionRemoveCount());
        } finally {
            statistics.setStatisticsEnabled(false);
        }

        assertEquals(jobBefore, jobService.getJob(jobId));
        assertEquals(jobBefore.updatedAt(), jobService.getJob(jobId).updatedAt());
        assertEquals(JobStatus.DISCOVERED, jobService.getJob(jobId).status());
        assertEquals(requirementsBefore, jobService.getRequirements(jobId));
        assertEquals(profileBefore, resumes.getProfile(profileId));
        assertEquals(tablesBefore, publicTableCount());
        assertEquals(rowsBefore, sourceRows());
        assertEquals("5", jdbc.queryForObject("select max(version) from flyway_schema_history", String.class));
        assertEquals(0, jdbc.queryForObject(
                "select count(*) from information_schema.tables where table_schema = 'public' and table_name in ('job_assessments','assessments')",
                Integer.class));
    }

    @Test
    void changedRequirementsAreObserved() throws Exception {
        Long profileId = saveProfile("Java backend engineer");
        Long jobId = persistJob("Role", "Java", List.of("Java"), List.of(), null);
        MatchResponse before = assessments.assess(jobId, profileId);
        assertEquals(List.of("java"), before.matchedRequiredSkills());

        jobService.replaceRequirements(jobId, new JobRequirementsRequest(List.of("Redis"), List.of(), null));
        MatchResponse after = assessments.assess(jobId, profileId);
        assertEquals(oracle(jobId, profileId), after);
        assertEquals(List.of("redis"), after.missingRequiredSkills());
        assertNotEquals(before.matchedRequiredSkills(), after.matchedRequiredSkills());
    }

    @Test
    void titleAndDescriptionChangesThatAffectEngineEvidenceAreObserved() throws Exception {
        Long profileId = saveProfile("""
                Experience
                Backend Engineer at Acme
                Java payments systems
                """);
        Long jobId = persistJob("Backend Engineer", "Java payments systems", List.of("java"), List.of(), null);
        MatchResponse before = assessments.assess(jobId, profileId);
        assertEquals(List.of("backend"), before.roleRelevance().matchedTerms());
        assertEquals(List.of("payments", "systems"), before.keywordRelevance().matchedTerms());

        updateJob(jobId, "Unrelated Role", "Java payments systems");
        MatchResponse titleOnly = assessments.assess(jobId, profileId);
        assertEquals(oracle(jobId, profileId), titleOnly);
        assertNotEquals(before.roleRelevance(), titleOnly.roleRelevance());
        assertEquals(before.keywordRelevance(), titleOnly.keywordRelevance());
        updateJob(jobId, "Unrelated Role", "unrelated prose");
        MatchResponse after = assessments.assess(jobId, profileId);
        assertEquals(oracle(jobId, profileId), after);
        assertNotEquals(before.roleRelevance(), after.roleRelevance());
        assertNotEquals(before.keywordRelevance(), after.keywordRelevance());
        assertEquals(titleOnly.roleRelevance(), after.roleRelevance());
    }

    @Test
    void candidateProfileSelectionStaysExplicitAfterAnotherUpload() throws Exception {
        Long first = saveProfile("Java backend engineer");
        Long jobId = persistJob("Role", "Java", List.of("Java"), List.of(), null);
        MatchResponse firstAssessment = assessments.assess(jobId, first);
        Long second = saveProfile("Redis cache engineer");
        MatchResponse stillFirst = assessments.assess(jobId, first);
        MatchResponse secondAssessment = assessments.assess(jobId, second);

        assertEquals(first, stillFirst.candidateProfileId());
        assertEquals(firstAssessment, stillFirst);
        assertEquals(second, secondAssessment.candidateProfileId());
        assertEquals(oracle(jobId, first), stillFirst);
        assertEquals(oracle(jobId, second), secondAssessment);
        assertNotEquals(stillFirst.matchedRequiredSkills(), secondAssessment.matchedRequiredSkills());
    }

    @Test
    void uncomputableAssessmentIs422AndDoesNotMutateSources() throws Exception {
        Long profileId = saveProfile("Java");
        var created = jobService.createJob(request("Blank Role", "blank-uncomputable", "no requirements yet"));
        var jobBefore = jobService.getJob(created.id());
        var profileBefore = resumes.getProfile(profileId);

        var response = post("/api/jobs/" + created.id() + "/assessment", "{\"candidateProfileId\":" + profileId + "}");
        assertEquals(422, response.statusCode(), response.body());
        JsonNode body = json.readTree(response.body());
        assertEquals(422, body.path("status").asInt());
        assertFalse(response.body().contains("MatchCannotBeComputedException"));
        assertEquals(jobBefore, jobService.getJob(created.id()));
        assertEquals(profileBefore, resumes.getProfile(profileId));
    }

    @Test
    void unexpectedEngineFailureIsGeneric500WithoutSourceMutation() throws Exception {
        Long profileId = saveProfile("Java");
        Long jobId = persistJob("Role", "Java", List.of("Java"), List.of(), null);
        var jobBefore = jobService.getJob(jobId);
        var profileBefore = resumes.getProfile(profileId);
        doThrow(new IllegalStateException("secret-engine-detail")).when(engine).match(any(), any());

        var response = post("/api/jobs/" + jobId + "/assessment", "{\"candidateProfileId\":" + profileId + "}");
        assertEquals(500, response.statusCode(), response.body());
        JsonNode body = json.readTree(response.body());
        assertEquals(500, body.path("status").asInt());
        assertFalse(response.body().contains("secret-engine-detail"));
        assertFalse(response.body().contains("IllegalStateException"));
        assertFalse(body.has("trace"));
        assertFalse(body.has("exception"));
        var legacy = post("/api/matches", "{\"candidateProfileId\":" + profileId + ",\"jobId\":" + jobId + "}");
        assertEquals(500, legacy.statusCode());
        assertFalse(legacy.body().contains("secret-engine-detail"));
        assertFalse(legacy.body().contains("IllegalStateException"));
        assertEquals(jobBefore, jobService.getJob(jobId));
        assertEquals(profileBefore, resumes.getProfile(profileId));
    }

    @Test
    void adversarialJsonTypesPreserveLegacyCoercionAndValidation() throws Exception {
        Long profile = saveProfile("Java");
        Long job = persistJob("Role", "", List.of("Java"), List.of(), null);
        String path = "/api/jobs/" + job + "/assessment";
        for (String value : List.of("null", "0", "-1", "true", "{}", "[]", "\"not-an-id\"",
                "9223372036854775808")) {
            var response = post(path, "{\"candidateProfileId\":" + value + "}");
            var legacy = post("/api/matches", "{\"jobId\":" + job + ",\"candidateProfileId\":" + value + "}");
            assertEquals(400, response.statusCode(), value + ": " + response.body());
            assertEquals(legacy.statusCode(), response.statusCode(), value);
        }
        for (String body : List.of("", "{}", "null", "[]", "true", "{")) {
            assertEquals(400, post(path, body).statusCode(), body);
        }
        // Characterize the application's existing numeric-string/decimal coercion, not a new DTO policy.
        for (String value : List.of("\"" + profile + "\"", profile + ".9")) {
            var response = post(path, "{\"candidateProfileId\":" + value + "}");
            var legacy = post("/api/matches", "{\"jobId\":" + job + ",\"candidateProfileId\":" + value + "}");
            assertEquals(legacy.statusCode(), response.statusCode(), value);
            assertEquals(200, response.statusCode(), response.body());
            assertEquals(profile.longValue(), json.readTree(response.body()).path("candidateProfileId").asLong());
            assertEquals(json.readTree(legacy.body()), json.readTree(response.body()));
        }
        assertEquals(404, post(path, "{\"candidateProfileId\":9223372036854775807}").statusCode());
        var unsupported = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "text/plain").POST(HttpRequest.BodyPublishers.ofString("candidateProfileId=1"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(415, unsupported.statusCode());
    }

    @Test
    void adversarialPathIdsUseExistingJobConventions() throws Exception {
        Long profile = saveProfile("Java");
        String body = "{\"candidateProfileId\":" + profile + "}";
        for (String value : List.of("0", "-1", "9223372036854775807")) {
            assertEquals(404, post("/api/jobs/" + value + "/assessment", body).statusCode(), value);
        }
        for (String value : List.of("abc", "1.5", "9223372036854775808", "99999999999999999999999999999999999999")) {
            assertEquals(400, post("/api/jobs/" + value + "/assessment", body).statusCode(), value);
        }
        assertEquals(404, post("/api/jobs//assessment", body).statusCode());
    }

    @Test
    void obviousDescriptionIsNotExtractedUntilExplicitlyRequested() throws Exception {
        Long profile = saveProfile("Java Spring Boot");
        Long job = jobService.createJob(request("Role", "explicit-extract",
                "Must have Java and Spring Boot, 2 years experience")).id();
        var before = sourceRows();
        assertEquals(422, post("/api/jobs/" + job + "/assessment",
                "{\"candidateProfileId\":" + profile + "}").statusCode());
        assertEquals(before, sourceRows());
        jobService.extractRequirements(job);
        assertHttpParity(job, profile);
    }

    @Test
    void computableZeroPreferredOnlyAndExperienceOnlyRemainSuccessful() throws Exception {
        Long profile = saveProfile("No recognized employment chronology");
        Long zero = persistJob("Role", "", List.of("Java"), List.of(), null);
        assertEquals(0, assessments.assess(zero, profile).overallScore().signum());
        assertHttpParity(zero, profile);
        Long preferred = persistJob("Role", "", List.of(), List.of("Java"), null);
        assertHttpParity(preferred, profile);
        Long experience = persistJob("Role", "", List.of(), List.of(), new BigDecimal("2"));
        assertHttpParity(experience, profile);
        assertEquals(MatchResult.Status.UNKNOWN, assessments.assess(experience, profile).experienceComparison().status());
        Long empty = persistJob("Role", "", List.of(), List.of(), BigDecimal.ZERO);
        assertEquals(422, post("/api/jobs/" + empty + "/assessment",
                "{\"candidateProfileId\":" + profile + "}").statusCode());
    }

    @Test
    void terminalJobsCanBeAssessedWithoutChangingDefaultRanking() throws Exception {
        Long profile = saveProfile("Java");
        Long eligible = persistJob("Role", "", List.of("Java"), List.of(), null);
        for (JobStatus status : List.of(JobStatus.OFFER, JobStatus.REJECTED, JobStatus.WITHDRAWN)) {
            Long terminal = persistJob("Role", "", List.of("Java"), List.of(), null);
            jobService.updateJobStatus(terminal, new UpdateJobStatusRequest(status));
            var before = sourceRows();
            var rankingPath = "/api/candidate-profiles/" + profile + "/job-rankings";
            var rankedBefore = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + rankingPath)).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertHttpParity(terminal, profile);
            var rankedAfter = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + rankingPath)).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, rankedBefore.statusCode());
            assertEquals(json.readTree(rankedBefore.body()), json.readTree(rankedAfter.body()));
            assertEquals(1, json.readTree(rankedAfter.body()).path("evaluatedJobCount").asInt());
            assertEquals(eligible.longValue(), json.readTree(rankedAfter.body()).path("rankedJobs").get(0).path("job").path("id").asLong());
            assertEquals(before, sourceRows());
        }
    }

    private void assertHttpParity(Long job, Long profile) throws Exception {
        var response = post("/api/jobs/" + job + "/assessment", "{\"candidateProfileId\":" + profile + "}");
        var legacy = post("/api/matches", "{\"jobId\":" + job + ",\"candidateProfileId\":" + profile + "}");
        assertEquals(200, response.statusCode(), response.body());
        assertEquals(200, legacy.statusCode(), legacy.body());
        assertEquals(json.readTree(legacy.body()), json.readTree(response.body()));
        assertEquals(json.readTree(json.writeValueAsString(oracle(job, profile))), json.readTree(response.body()));
    }

    private List<List<String>> sourceRows() {
        return List.of("jobs", "job_skills", "candidate_profiles", "resumes").stream()
                .map(table -> jdbc.queryForList("select row_to_json(t)::text from " + table + " t order by row_to_json(t)::text", String.class))
                .toList();
    }

    private MatchResponse oracle(Long jobId, Long profileId) {
        var job = jobService.matchingSnapshot(jobId);
        var candidate = resumes.matchingSnapshot(profileId);
        return MatchResponse.from(
                candidate.id(),
                job.id(),
                engine.version(),
                MatchingVocabulary.standard().version(),
                candidate.parserVersion(),
                candidate.assessedOn(),
                job.updatedAt(),
                engine.match(job, candidate)
        );
    }

    private Long persistJob(String title, String description, List<String> required, List<String> preferred, BigDecimal minYears) {
        var created = jobService.createJob(request(title, title.toLowerCase().replace(' ', '-'), description));
        jobService.replaceRequirements(created.id(), new JobRequirementsRequest(required, preferred, minYears));
        return created.id();
    }

    private JobResponse updateJob(Long jobId, String title, String description) {
        var current = jobService.getJob(jobId);
        return jobService.updateJob(jobId, new UpdateJobRequest(
                title, current.company(), current.location(), current.jobUrl(), description, current.source(), current.externalJobId()));
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

    private static CreateJobRequest request(String title, String externalJobId, String description) {
        return new CreateJobRequest(title, "Acme", "Remote", "https://example.com/jobs/" + externalJobId,
                description, "TESTCONTAINERS", externalJobId);
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private int publicTableCount() {
        return jdbc.queryForObject("select count(*) from information_schema.tables where table_schema = 'public'", Integer.class);
    }
}
