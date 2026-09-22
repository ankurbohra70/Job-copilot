package com.jobcopilot.discovery;

import com.jobcopilot.application.ApplicationController;
import com.jobcopilot.application.LiveOpportunityController;
import com.jobcopilot.common.web.ApiErrorHandler;
import com.jobcopilot.discovery.lever.LeverFetchResult;
import com.jobcopilot.discovery.lever.LeverPosting;
import com.jobcopilot.discovery.lever.LeverPostingGateway;
import com.jobcopilot.discovery.workday.WorkdayFetchResult;
import com.jobcopilot.discovery.workday.WorkdayJobDetail;
import com.jobcopilot.discovery.workday.WorkdayPostingGateway;
import com.jobcopilot.discovery.workday.WorkdaySearchPosting;
import com.jobcopilot.matching.JobAssessmentController;
import com.jobcopilot.matching.JobRankingController;
import com.jobcopilot.resume.CandidateProfileController;
import com.jobcopilot.resume.JobSearchPreferenceController;
import com.jobcopilot.resume.ResumeRouteController;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WorkdayJc009ParityIntegrationTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @MockitoBean LeverPostingGateway leverGateway;
    @MockitoBean WorkdayPostingGateway workdayGateway;
    @Autowired JobSourceController sourceController;
    @Autowired CandidateProfileController profileController;
    @Autowired JobSearchPreferenceController preferenceController;
    @Autowired ResumeRouteController routeController;
    @Autowired JobAssessmentController assessmentController;
    @Autowired JobRankingController rankingController;
    @Autowired ApplicationController applicationController;
    @Autowired LiveOpportunityController opportunityController;
    @Autowired JdbcTemplate jdbc;

    private final JsonMapper json = JsonMapper.builder().build();
    private MockMvc mvc;

    @BeforeEach void setUp() {
        jdbc.execute("TRUNCATE TABLE job_sources, jobs, candidate_profiles, resumes RESTART IDENTITY CASCADE");
        reset(leverGateway, workdayGateway);
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mvc = MockMvcBuilders.standaloneSetup(sourceController, profileController, preferenceController,
                        routeController, assessmentController, rankingController, applicationController,
                        opportunityController)
                .setControllerAdvice(new ApiErrorHandler()).setValidator(validator).build();
    }

    @Test void workdayAndLeverShareTheCompleteJc009OpportunityFlowWithoutProviderBias() throws Exception {
        long leverSource = createLeverSource();
        long workdaySource = createWorkdaySource();
        respondWithEquivalentJobs();

        mvc.perform(post("/api/job-sources/{id}/sync", leverSource))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUCCEEDED"));
        mvc.perform(post("/api/job-sources/{id}/sync", workdaySource))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUCCEEDED"));

        long leverJob = jobId("LEVER");
        long workdayJob = jobId("WORKDAY");
        assertNotEquals(leverJob, workdayJob);
        mvc.perform(get("/api/job-sources/{id}/listings", leverSource))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].extractionState").value("CURRENT"));
        mvc.perform(get("/api/job-sources/{id}/listings", workdaySource))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].extractionState").value("CURRENT"));

        long profile = createCandidate();
        confirmCandidate(profile);
        long preferenceId = configurePreference(profile);
        configureResumeRoute(profile);

        JsonNode leverMatch = assessment(leverJob, profile);
        JsonNode workdayMatch = assessment(workdayJob, profile);
        assertMatchParity(leverMatch, workdayMatch);

        JsonNode ranking = getJson("/api/candidate-profiles/%d/job-rankings".formatted(profile));
        assertEquals(2, ranking.path("evaluatedJobCount").asInt());
        assertEquals(2, ranking.path("computableJobCount").asInt());
        assertMatchParity(rankedMatch(ranking, leverJob), rankedMatch(ranking, workdayJob));
        List<Long> expectedTieOrder = jdbc.queryForList(
                "SELECT id FROM jobs ORDER BY created_at DESC, id DESC", Long.class);
        assertEquals(expectedTieOrder, ids(ranking.path("rankedJobs")));

        JsonNode leverDecision = decision(leverJob, profile);
        JsonNode workdayDecision = decision(workdayJob, profile);
        assertDecisionParity(leverDecision, workdayDecision);
        JsonNode leverReadiness = readiness(leverJob, profile);
        JsonNode workdayReadiness = readiness(workdayJob, profile);
        assertEquals("READY", leverReadiness.path("readiness").asText());
        assertReadinessParity(leverReadiness, workdayReadiness);

        JsonNode opportunities = opportunities(profile);
        assertEquals(2, opportunities.path("liveListingCount").asInt());
        assertEquals(2, opportunities.path("eligibleListingCount").asInt());
        assertEquals(2, opportunities.path("computableJobCount").asInt());
        assertEquals(2, opportunities.path("opportunities").size());
        assertEquals(2, opportunities.path("profileRevision").asLong());
        assertEquals(preferenceId, opportunities.path("preferenceId").asLong());
        assertOpportunityParity(opportunity(opportunities, leverJob), opportunity(opportunities, workdayJob));
        assertEquals(expectedTieOrder, ids(opportunities.path("opportunities")));

        mvc.perform(put("/api/candidate-profiles/{id}/confirmation", profile)
                        .contentType("application/json").content(confirmation(1)))
                .andExpect(status().isConflict());
        JsonNode afterStaleCandidateWrite = opportunities(profile);
        assertEquals(2, afterStaleCandidateWrite.path("profileRevision").asLong());
        assertEquals(preferenceId, afterStaleCandidateWrite.path("preferenceId").asLong());

        Map<Long, String> fingerprints = jdbc.query("""
                SELECT job_id, extraction_fingerprint FROM external_job_listings
                """, result -> {
            Map<Long, String> values = new LinkedHashMap<>();
            while (result.next()) values.put(result.getLong(1), result.getString(2));
            return values;
        });
        jdbc.update("UPDATE external_job_listings SET extraction_fingerprint = 'stale'");
        JsonNode stale = opportunities(profile);
        assertEquals(2, stale.path("liveListingCount").asInt());
        assertEquals(0, stale.path("eligibleListingCount").asInt());
        assertEquals(2, stale.path("ineligibleListingCount").asInt());
        assertEquals("NOT_READY", readiness(leverJob, profile).path("readiness").asText());
        assertEquals("NOT_READY", readiness(workdayJob, profile).path("readiness").asText());
        fingerprints.forEach((job, fingerprint) -> jdbc.update(
                "UPDATE external_job_listings SET extraction_fingerprint = ? WHERE job_id = ?", fingerprint, job));

        doAnswer(invocation ->
                new WorkdayFetchResult.Failure(invocation.getArgument(0), WorkdayFetchResult.Operation.SEARCH,
                        invocation.getArgument(1), null, WorkdayFetchResult.FailureKind.TIMEOUT,
                        null, Duration.ZERO)).when(workdayGateway).searchPage(any(), any());
        mvc.perform(post("/api/job-sources/{id}/sync", workdaySource))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureCode").value("TIMEOUT"));
        JsonNode afterFailure = opportunities(profile);
        assertEquals(2, afterFailure.path("opportunities").size());
        assertEquals(leverSource, opportunity(afterFailure, leverJob).path("listing").path("sourceId").asLong());

        doAnswer(invocation ->
                new WorkdayFetchResult.SearchSuccess(invocation.getArgument(0), invocation.getArgument(1),
                        0, List.of(), Duration.ZERO)).when(workdayGateway).searchPage(any(), any());
        mvc.perform(post("/api/job-sources/{id}/sync", workdaySource))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUCCEEDED"));
        JsonNode afterClosure = opportunities(profile);
        assertEquals(1, afterClosure.path("opportunities").size());
        assertEquals(leverJob, afterClosure.path("opportunities").get(0).path("job").path("id").asLong());
        assertEquals("NOT_READY", readiness(workdayJob, profile).path("readiness").asText());
        assertEquals("READY", readiness(leverJob, profile).path("readiness").asText());
    }

    private long createLeverSource() throws Exception {
        return id(mvc.perform(post("/api/job-sources").contentType("application/json").content("""
                {"provider":"LEVER","region":"GLOBAL","sourceKey":"acme-lever",
                 "companyName":"Acme","enabled":true}
                """)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private long createWorkdaySource() throws Exception {
        return id(mvc.perform(post("/api/job-sources").contentType("application/json").content("""
                {"provider":"WORKDAY","companyName":"Acme","enabled":true,
                 "endpointHost":"acme.wd5.myworkdayjobs.com","tenantKey":"acme",
                 "siteKey":"External","locale":"en-US"}
                """)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private void respondWithEquivalentJobs() {
        String description = "Required: Java. Minimum 3 years experience.";
        LeverPosting lever = LeverPostingMapperTest.posting("lever-job", "Backend Engineer",
                new LeverPosting.Categories("Bengaluru", List.of(), null, null, null),
                new LeverPosting.Content(null, description, List.of(), null, null));
        when(leverGateway.fetchPage(any(), any())).thenAnswer(invocation ->
                new LeverFetchResult.Success(invocation.getArgument(0), invocation.getArgument(1),
                        List.of(lever), Duration.ZERO));
        when(workdayGateway.searchPage(any(), any())).thenAnswer(invocation ->
                new WorkdayFetchResult.SearchSuccess(invocation.getArgument(0), invocation.getArgument(1), 1,
                        List.of(new WorkdaySearchPosting("Backend Engineer", "/job/workday-job",
                                "Bengaluru", null, null, List.of())), Duration.ZERO));
        when(workdayGateway.fetchDetail(any(), any())).thenAnswer(invocation ->
                new WorkdayFetchResult.DetailSuccess(invocation.getArgument(0), invocation.getArgument(1),
                        new WorkdayJobDetail("workday-job", "Backend Engineer", description, "Bengaluru",
                                null, null, null, null, null, null, null,
                                URI.create("https://acme.wd5.myworkdayjobs.com/External/job/workday-job"),
                                true, true), Duration.ZERO));
    }

    private long createCandidate() throws Exception {
        return id(mvc.perform(post("/api/candidate-profiles").contentType("application/json").content("""
                {"fullName":"Candidate","email":"candidate@example.com","location":"Bengaluru",
                 "totalRelevantExperienceMonths":60,"workAuthorization":"YES",
                 "sponsorshipRequired":"NO"}
                """)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private void confirmCandidate(long profile) throws Exception {
        mvc.perform(put("/api/candidate-profiles/{id}/confirmation", profile)
                        .contentType("application/json").content(confirmation(1)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(2));
    }

    private String confirmation(long revision) {
        return """
                {"expectedRevision":%d,"profile":{"skills":["java"],"totalExperienceMonths":60,
                 "observedExperienceMonths":60,"experienceAssessment":"KNOWN","workExperience":[],
                 "education":[],"projects":[],"keywords":["backend","engineer"],
                 "roleCategories":["backend engineer"],"evidence":[],"warnings":[]},
                 "facts":{"fullName":"Candidate","email":"candidate@example.com","location":"Bengaluru",
                 "totalRelevantExperienceMonths":60,"workAuthorization":"YES","sponsorshipRequired":"NO"}}
                """.formatted(revision);
    }

    private long configurePreference(long profile) throws Exception {
        String body = mvc.perform(put("/api/candidate-profiles/{id}/job-search-preference", profile)
                        .contentType("application/json").content("""
                                {"defaultResumeStrategy":"PRECISION","targetRoles":["Backend Engineer"],
                                 "excludedRoles":[],"preferredLocations":["Bengaluru"],
                                 "acceptableWorkArrangements":[],"freshnessDays":7}
                                """))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).path("id").asLong();
    }

    private void configureResumeRoute(long profile) throws Exception {
        long resume = jdbc.queryForObject("""
                INSERT INTO resumes(file_name, size_bytes, media_type, extracted_text, page_count,
                    extractor_version, created_at)
                VALUES ('parity.pdf', 100, 'application/pdf', 'Java Backend Engineer', 1,
                    'pdfbox-v1', CURRENT_TIMESTAMP) RETURNING id
                """, Long.class);
        mvc.perform(post("/api/candidate-profiles/{id}/resume-routes", profile)
                        .contentType("application/json").content("""
                                {"resumeId":%d,"strategy":"PRECISION","defaultRoute":true,
                                 "variantLabel":"Parity precision","approved":true}
                                """.formatted(resume)))
                .andExpect(status().isCreated());
    }

    private long jobId(String provider) {
        return jdbc.queryForObject("""
                SELECT l.job_id FROM external_job_listings l
                JOIN job_sources s ON s.id = l.job_source_id WHERE s.provider = ?
                """, Long.class, provider);
    }

    private JsonNode assessment(long job, long profile) throws Exception {
        String body = mvc.perform(post("/api/jobs/{id}/assessment", job)
                        .contentType("application/json").content("{\"candidateProfileId\":%d}".formatted(profile)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    private JsonNode decision(long job, long profile) throws Exception {
        return getJson("/api/jobs/%d/application-decision?candidateProfileId=%d".formatted(job, profile));
    }

    private JsonNode readiness(long job, long profile) throws Exception {
        return getJson("/api/jobs/%d/application-readiness?candidateProfileId=%d".formatted(job, profile));
    }

    private JsonNode opportunities(long profile) throws Exception {
        return getJson("/api/candidate-profiles/%d/opportunities".formatted(profile));
    }

    private JsonNode getJson(String path) throws Exception {
        return json.readTree(mvc.perform(get(path)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private static JsonNode rankedMatch(JsonNode ranking, long job) {
        for (JsonNode value : ranking.path("rankedJobs"))
            if (value.path("job").path("id").asLong() == job) return value.path("match");
        throw new AssertionError("ranked job not found: " + job);
    }

    private static JsonNode opportunity(JsonNode page, long job) {
        for (JsonNode value : page.path("opportunities"))
            if (value.path("job").path("id").asLong() == job) return value;
        throw new AssertionError("opportunity not found: " + job);
    }

    private static List<Long> ids(JsonNode values) {
        java.util.ArrayList<Long> ids = new java.util.ArrayList<>();
        for (JsonNode value : values) ids.add(value.path("job").path("id").asLong());
        return List.copyOf(ids);
    }

    private static void assertMatchParity(JsonNode left, JsonNode right) {
        for (String field : List.of("overallScore", "recommendation", "matchedRequiredSkills",
                "missingRequiredSkills", "matchedPreferredSkills", "unmatchedPreferredSkills",
                "experienceComparison", "roleRelevance", "keywordRelevance", "appliedCaps",
                "strengths", "gaps", "warnings", "unassessedFactors"))
            assertEquals(left.path(field), right.path(field), field);
    }

    private static void assertDecisionParity(JsonNode left, JsonNode right) {
        for (String field : List.of("decision", "reasons", "overallScore", "recommendation",
                "candidateInputRequired", "recommendedResumeStrategy", "policyVersion"))
            assertEquals(left.path(field), right.path(field), field);
    }

    private static void assertReadinessParity(JsonNode left, JsonNode right) {
        assertEquals(left.path("readiness"), right.path("readiness"));
        assertDecisionParity(left.path("decision"), right.path("decision"));
        assertEquals(checkStatuses(left), checkStatuses(right));
    }

    private static Map<String, String> checkStatuses(JsonNode readiness) {
        Map<String, String> values = new LinkedHashMap<>();
        for (JsonNode check : readiness.path("checks"))
            values.put(check.path("code").asText(), check.path("status").asText());
        return values;
    }

    private static void assertOpportunityParity(JsonNode left, JsonNode right) {
        assertMatchParity(left.path("match"), right.path("match"));
        assertDecisionParity(left.path("decision"), right.path("decision"));
        assertReadinessParity(left.path("readiness"), right.path("readiness"));
    }

    private long id(String body) throws Exception {
        return json.readTree(body).path("id").asLong();
    }
}
