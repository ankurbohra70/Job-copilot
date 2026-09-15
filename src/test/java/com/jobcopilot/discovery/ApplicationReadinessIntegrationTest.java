package com.jobcopilot.discovery;

import com.jobcopilot.application.ApplicationController;
import com.jobcopilot.common.web.ApiErrorHandler;
import com.jobcopilot.resume.CandidateProfileController;
import com.jobcopilot.resume.JobSearchPreferenceController;
import com.jobcopilot.resume.ResumeRouteController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@Testcontainers
class ApplicationReadinessIntegrationTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired ApplicationController applicationController;
    @Autowired CandidateProfileController candidateProfileController;
    @Autowired JobSearchPreferenceController preferenceController;
    @Autowired ResumeRouteController routeController;
    @Autowired JdbcTemplate jdbc;
    private MockMvc mvc;
    private final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach void setupMvc() {
        mvc = MockMvcBuilders.standaloneSetup(applicationController, candidateProfileController,
                preferenceController, routeController).setControllerAdvice(new ApiErrorHandler()).build();
    }

    @Test void readinessReadsCurrentJc008StateAndRepeatedGetsAreObservational() throws Exception {
        long profileId = id(mvc.perform(post("/api/candidate-profiles").contentType("application/json").content("""
                {"fullName":"Candidate","email":"candidate@example.com","location":"Bengaluru",
                 "totalRelevantExperienceMonths":60,"workAuthorization":"YES",
                 "sponsorshipRequired":"NO"}
                """)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        mvc.perform(put("/api/candidate-profiles/{id}/confirmation", profileId)
                        .contentType("application/json").content("""
                                {"expectedRevision":1,"profile":{"skills":[],"totalExperienceMonths":60,
                                 "observedExperienceMonths":60,"experienceAssessment":"KNOWN",
                                 "workExperience":[],"education":[],"projects":[],"keywords":[],
                                 "roleCategories":[],"evidence":[],"warnings":[]},
                                 "facts":{"fullName":"Candidate","email":"candidate@example.com",
                                 "location":"Bengaluru","totalRelevantExperienceMonths":60,
                                 "workAuthorization":"YES","sponsorshipRequired":"NO"}}
                                """))
                .andExpect(status().isOk());
        mvc.perform(put("/api/candidate-profiles/{id}/job-search-preference", profileId)
                        .contentType("application/json").content("""
                                {"defaultResumeStrategy":"PRECISION","targetRoles":["Backend Engineer"],
                                 "excludedRoles":[],"preferredLocations":["Bengaluru"],
                                 "acceptableWorkArrangements":[],"freshnessDays":7}
                                """))
                .andExpect(status().isOk());

        long resumeId = jdbc.queryForObject("""
                INSERT INTO resumes(file_name, size_bytes, media_type, extracted_text, page_count,
                    extractor_version, created_at)
                VALUES ('stable.pdf', 100, 'application/pdf', 'Backend Engineer', 1, 'pdfbox-v1', CURRENT_TIMESTAMP)
                RETURNING id
                """, Long.class);
        mvc.perform(post("/api/candidate-profiles/{id}/resume-routes", profileId)
                        .contentType("application/json").content("""
                                {"resumeId":%d,"strategy":"PRECISION","defaultRoute":true,
                                 "variantLabel":"Stable precision","approved":true}
                                """.formatted(resumeId)))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/candidate-profiles/{id}", profileId)).andExpect(status().isOk());
        mvc.perform(get("/api/candidate-profiles/{id}/job-search-preference", profileId)).andExpect(status().isOk());
        mvc.perform(get("/api/candidate-profiles/{id}/resume-routes", profileId)).andExpect(status().isOk());

        String description = "Required: at least 3 years experience";
        long jobId = jdbc.queryForObject("""
                INSERT INTO jobs(title, company, location, job_url, description, source, external_job_id,
                    status, min_years_experience, created_at, updated_at)
                VALUES ('Backend Engineer', 'Acme', 'Bengaluru', 'https://jobs.example.test/1', ?,
                    'LEVER', 'job-1', 'DISCOVERED', 3, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                RETURNING id
                """, Long.class, description);
        long sourceId = jdbc.queryForObject("""
                INSERT INTO job_sources(provider, region, source_key, company_name, enabled, created_at, updated_at)
                VALUES ('LEVER', 'GLOBAL', 'qa-source', 'Acme', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                RETURNING id
                """, Long.class);
        String fingerprint = "jc005-v1:sha256:"
                + ProviderContentDigest.hash("description-v1", List.of(description));
        jdbc.update("""
                INSERT INTO external_job_listings(job_source_id, job_id, external_job_id, availability,
                    provider_content_digest, hosted_job_url, apply_url, extraction_fingerprint,
                    first_seen_at, last_seen_at, last_verified_at)
                VALUES (?, ?, 'job-1', 'LIVE', 'digest', 'https://jobs.example.test/1',
                    'https://apply.example.test/1', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, sourceId, jobId, fingerprint);

        Map<String, Object> before = state(jobId, profileId);
        mvc.perform(get("/api/jobs/{id}/application-decision", jobId).param("candidateProfileId", String.valueOf(profileId)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.decision").value("APPLY_PRECISION"));
        mvc.perform(get("/api/jobs/{id}/application-readiness", jobId).param("candidateProfileId", String.valueOf(profileId)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.readiness").value("READY"));
        mvc.perform(get("/api/jobs/{id}/application-readiness", jobId).param("candidateProfileId", String.valueOf(profileId)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.readiness").value("READY"));
        assertEquals(before, state(jobId, profileId));

        jdbc.update("UPDATE external_job_listings SET availability = 'CLOSED' WHERE job_id = ?", jobId);
        mvc.perform(get("/api/jobs/{id}/application-readiness", jobId).param("candidateProfileId", String.valueOf(profileId)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.readiness").value("NOT_READY"));

        jdbc.update("""
                UPDATE external_job_listings SET availability = 'LIVE', extraction_fingerprint = 'stale',
                    last_seen_at = CURRENT_TIMESTAMP, last_verified_at = CURRENT_TIMESTAMP WHERE job_id = ?
                """, jobId);
        mvc.perform(get("/api/jobs/{id}/application-readiness", jobId).param("candidateProfileId", String.valueOf(profileId)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.readiness").value("NOT_READY"));

        jdbc.update("""
                UPDATE external_job_listings SET extraction_fingerprint = ?, last_seen_at = CURRENT_TIMESTAMP,
                    last_verified_at = CURRENT_TIMESTAMP WHERE job_id = ?
                """, fingerprint, jobId);
        jdbc.update("UPDATE job_sources SET enabled = false WHERE id = ?", sourceId);
        mvc.perform(get("/api/jobs/{id}/application-readiness", jobId).param("candidateProfileId", String.valueOf(profileId)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.readiness").value("READY"));

        mvc.perform(get("/api/jobs/999999/application-readiness").param("candidateProfileId", String.valueOf(profileId)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/jobs/{id}/application-decision", jobId).param("candidateProfileId", "999999"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/jobs/{id}/application-readiness", jobId).param("candidateProfileId", "not-a-number"))
                .andExpect(status().isBadRequest());
    }

    private long id(String body) throws Exception { return json.readTree(body).path("id").asLong(); }

    private Map<String, Object> state(long jobId, long profileId) {
        return jdbc.queryForMap("""
                SELECT j.status, l.availability, l.extraction_fingerprint,
                    (SELECT count(*) FROM candidate_profiles WHERE id = ?) AS profiles,
                    (SELECT count(*) FROM job_search_preferences WHERE candidate_profile_id = ?) AS preferences,
                    (SELECT count(*) FROM resume_routes WHERE candidate_profile_id = ?) AS routes,
                    (SELECT count(*) FROM job_source_sync_runs) AS sync_runs
                FROM jobs j JOIN external_job_listings l ON l.job_id = j.id WHERE j.id = ?
                """, profileId, profileId, profileId, jobId);
    }
}
