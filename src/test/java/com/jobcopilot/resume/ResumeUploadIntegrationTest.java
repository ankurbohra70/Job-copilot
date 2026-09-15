package com.jobcopilot.resume;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.servlet.multipart.max-file-size=4KB", "spring.servlet.multipart.max-request-size=5KB", "resume.processing.max-bytes=4096"})
@Testcontainers
class ResumeUploadIntegrationTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",POSTGRES::getJdbcUrl); r.add("spring.datasource.username",POSTGRES::getUsername); r.add("spring.datasource.password",POSTGRES::getPassword);
    }
    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate jdbc;
    private final HttpClient client = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();
    private HttpResponse<String> request(String method, String path, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type","application/json").method(method,HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> upload(byte[] bytes) throws Exception {
        String boundary = "JC003Boundary";
        var body = HttpRequest.BodyPublishers.concat(
                HttpRequest.BodyPublishers.ofString("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"resume.pdf\"\r\nContent-Type: application/pdf\r\n\r\n"),
                HttpRequest.BodyPublishers.ofByteArray(bytes),
                HttpRequest.BodyPublishers.ofString("\r\n--" + boundary + "--\r\n"));
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/resumes"))
                .header("Content-Type","multipart/form-data; boundary=" + boundary).POST(body).build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void completeUploadRequirementsAndMatchingSlice() throws Exception {
        var uploaded = upload(PdfBoxResumeTextExtractorTest.pdf("Experience\nBackend Engineer at Acme\nJan 2020 - Dec 2022\n- Built Java and PostgreSQL services with reliable tests",false));
        assertEquals(201,uploaded.statusCode(),uploaded.body());
        var resume = json.readTree(uploaded.body());
        assertFalse(uploaded.body().contains("extractedText"));
        assertEquals(3.0,resume.path("candidateProfile").path("totalYearsExperience").asDouble());
        assertEquals("DRAFT", resume.path("candidateProfile").path("status").asText());
        assertEquals(1, resume.path("candidateProfile").path("revision").asLong());
        long profileId = resume.path("candidateProfile").path("id").asLong();
        assertEquals(200,request("GET",uploaded.headers().firstValue("Location").orElseThrow(),"").statusCode());
        assertEquals(200,request("GET","/api/candidate-profiles/" + profileId,"").statusCode());
        String confirmation = """
                {"expectedRevision":1,"profile":%s,"facts":{"fullName":"Candidate",
                 "workAuthorization":"YES","sponsorshipRequired":"NO"}}
                """.formatted(resume.path("candidateProfile").path("profile"));
        var confirmed = request("PUT", "/api/candidate-profiles/" + profileId + "/confirmation", confirmation);
        assertEquals(200, confirmed.statusCode(), confirmed.body());
        assertEquals("CONFIRMED", json.readTree(confirmed.body()).path("status").asText());
        assertEquals(2, json.readTree(confirmed.body()).path("revision").asLong());
        var created = request("POST","/api/jobs","{\"title\":\"Backend Engineer\",\"company\":\"Acme\"}");
        assertEquals(201,created.statusCode(),created.body());
        long jobId = json.readTree(created.body()).path("id").asLong();
        var requirements = request("PUT","/api/jobs/" + jobId + "/requirements",
                "{\"requiredSkills\":[\"Java\"],\"preferredSkills\":[\"Postgres\"],\"minYearsExperience\":1}");
        assertEquals(200,requirements.statusCode(),requirements.body());
        var match = request("POST","/api/matches","{\"candidateProfileId\":" + profileId + ",\"jobId\":" + jobId + "}");
        assertEquals(200,match.statusCode(),match.body());
        assertEquals(100.0,json.readTree(match.body()).path("overallScore").asDouble());
        assertEquals("STRONG_MATCH",json.readTree(match.body()).path("recommendation").asText());
        assertEquals(200,request("PUT","/api/jobs/" + jobId,"{\"title\":\"Updated\",\"company\":\"Acme\"}").statusCode());
        assertTrue(request("GET","/api/jobs/" + jobId + "/requirements","").body().contains("java"));
    }
    @Test void actualServletRejectsOversizedFileWithApiError() throws Exception {
        var response = upload(new byte[4097]);
        assertEquals(413,response.statusCode(),response.body());
        assertEquals(413,json.readTree(response.body()).path("status").asInt());
    }

    @Test void uploadCorrectionAndConfirmationFlowIntoAuthoritativeLiveOpportunities() throws Exception {
        var uploaded = upload(PdfBoxResumeTextExtractorTest.pdf(
                "Experience\nBackend Engineer at Acme\nJan 2020 - Dec 2022\n- Built Java PostgreSQL payments services",
                false));
        assertEquals(201, uploaded.statusCode(), uploaded.body());
        var resume = json.readTree(uploaded.body());
        long profileId = resume.path("candidateProfile").path("id").asLong();
        long resumeId = resume.path("id").asLong();

        var created = request("POST", "/api/jobs", """
                {"title":"Backend Engineer","company":"Acme","location":"Bengaluru",
                 "jobUrl":"https://jobs.example.test/authority","description":"Java backend payments",
                 "source":"LEVER","externalJobId":"authority-job"}
                """);
        assertEquals(201, created.statusCode(), created.body());
        long jobId = json.readTree(created.body()).path("id").asLong();
        assertEquals(200, request("PUT", "/api/jobs/" + jobId + "/requirements", """
                {"requiredSkills":["Java"],"preferredSkills":["Postgres"],"minYearsExperience":1}
                """).statusCode());

        assertEquals(409, request("POST", "/api/matches",
                "{\"candidateProfileId\":" + profileId + ",\"jobId\":" + jobId + "}").statusCode());
        assertEquals(409, request("GET", "/api/jobs/" + jobId
                + "/application-readiness?candidateProfileId=" + profileId, "").statusCode());
        assertEquals(409, request("GET", "/api/candidate-profiles/" + profileId + "/opportunities", "").statusCode());

        String correctedWithoutJava = """
                {"expectedRevision":1,"profile":{"skills":["postgresql"],"totalExperienceMonths":null,
                 "observedExperienceMonths":36,"experienceAssessment":"UNKNOWN","workExperience":[],
                 "education":[],"projects":[],"keywords":[],"roleCategories":["backend"],
                 "evidence":[],"warnings":[]},"facts":{"fullName":"Candidate",
                 "email":"candidate@example.com","location":"Bengaluru","currentTitle":"Backend Engineer",
                 "totalRelevantExperienceMonths":72,"workAuthorization":"YES","sponsorshipRequired":"NO"}}
                """;
        var confirmed = request("PUT", "/api/candidate-profiles/" + profileId + "/confirmation",
                correctedWithoutJava);
        assertEquals(200, confirmed.statusCode(), confirmed.body());
        var withoutFallback = request("POST", "/api/matches",
                "{\"candidateProfileId\":" + profileId + ",\"jobId\":" + jobId + "}");
        assertEquals(200, withoutFallback.statusCode(), withoutFallback.body());
        assertEquals("UNKNOWN", json.readTree(withoutFallback.body())
                .path("experienceComparison").path("status").asText());
        assertEquals("java", json.readTree(withoutFallback.body()).path("missingRequiredSkills").get(0).asText());

        String authoritativeCorrection = """
                {"expectedRevision":2,"profile":{"skills":["java","postgresql"],"totalExperienceMonths":36,
                 "observedExperienceMonths":36,"experienceAssessment":"KNOWN","workExperience":[],
                 "education":[],"projects":[],"keywords":["payments"],"roleCategories":["backend"],
                 "evidence":[],"warnings":[]},"facts":{"fullName":"Candidate",
                 "email":"candidate@example.com","location":"Bengaluru","currentTitle":"Backend Engineer",
                 "totalRelevantExperienceMonths":6,"workAuthorization":"YES","sponsorshipRequired":"NO"}}
                """;
        var corrected = request("PUT", "/api/candidate-profiles/" + profileId + "/confirmation",
                authoritativeCorrection);
        assertEquals(200, corrected.statusCode(), corrected.body());
        assertEquals(3, json.readTree(corrected.body()).path("revision").asLong());

        var authoritativeMatch = request("POST", "/api/matches",
                "{\"candidateProfileId\":" + profileId + ",\"jobId\":" + jobId + "}");
        assertEquals(200, authoritativeMatch.statusCode(), authoritativeMatch.body());
        assertEquals(3.0, json.readTree(authoritativeMatch.body())
                .path("experienceComparison").path("candidateYears").asDouble());
        assertEquals("STRONG_MATCH", json.readTree(authoritativeMatch.body()).path("recommendation").asText());
        assertEquals(3, json.readTree(authoritativeMatch.body()).path("profileRevision").asLong());

        assertEquals(200, request("PUT", "/api/candidate-profiles/" + profileId + "/job-search-preference", """
                {"defaultResumeStrategy":"PRECISION","targetRoles":["Backend Engineer"],
                 "excludedRoles":[],"preferredLocations":["Bengaluru"],
                 "acceptableWorkArrangements":[],"freshnessDays":7}
                """).statusCode());
        assertEquals(201, request("POST", "/api/candidate-profiles/" + profileId + "/resume-routes", """
                {"resumeId":%d,"strategy":"PRECISION","defaultRoute":true,
                 "variantLabel":"Confirmed resume","approved":true}
                """.formatted(resumeId)).statusCode());

        long sourceId = jdbc.queryForObject("""
                INSERT INTO job_sources(provider, region, source_key, company_name, enabled, created_at, updated_at)
                VALUES ('LEVER', 'GLOBAL', ?, 'Acme', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                RETURNING id
                """, Long.class, "authority-source-" + profileId);
        String description = "Java backend payments";
        jdbc.update("""
                INSERT INTO external_job_listings(job_source_id, job_id, external_job_id, availability,
                    provider_content_digest, hosted_job_url, apply_url, extraction_fingerprint,
                    first_seen_at, last_seen_at, last_verified_at)
                VALUES (?, ?, 'authority-job', 'LIVE', 'digest', 'https://jobs.example.test/authority',
                    'https://apply.example.test/authority', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, sourceId, jobId, extractionFingerprint(description));

        var opportunities = request("GET", "/api/candidate-profiles/" + profileId + "/opportunities", "");
        assertEquals(200, opportunities.statusCode(), opportunities.body());
        var page = json.readTree(opportunities.body());
        assertEquals(3, page.path("profileRevision").asLong());
        assertEquals(1, page.path("liveListingCount").asInt());
        assertEquals(1, page.path("eligibleListingCount").asInt());
        assertEquals(1, page.path("pageResultCount").asInt());
        var opportunity = page.path("opportunities").get(0);
        assertEquals(jobId, opportunity.path("job").path("id").asLong());
        assertEquals("APPLY_PRECISION", opportunity.path("decision").path("decision").asText());
        assertEquals("READY", opportunity.path("readiness").path("readiness").asText());
        assertEquals("https://apply.example.test/authority",
                opportunity.path("listing").path("applyUrl").asText());
        assertEquals(3.0, opportunity.path("match").path("experienceComparison")
                .path("candidateYears").asDouble());

        var jobDecision = request("GET", "/api/jobs/" + jobId
                + "/application-decision?candidateProfileId=" + profileId, "");
        var jobReadiness = request("GET", "/api/jobs/" + jobId
                + "/application-readiness?candidateProfileId=" + profileId, "");
        assertEquals(200, jobDecision.statusCode(), jobDecision.body());
        assertEquals(200, jobReadiness.statusCode(), jobReadiness.body());
        assertEquals(json.readTree(jobDecision.body()), opportunity.path("decision"));
        assertEquals(json.readTree(jobReadiness.body()), opportunity.path("readiness"));

        var preference = request("GET", "/api/candidate-profiles/" + profileId + "/job-search-preference", "");
        assertEquals(200, preference.statusCode());
        assertEquals("Backend Engineer", json.readTree(preference.body()).path("preference")
                .path("targetRoles").get(0).asText());
    }
    @Test void actualServletRejectsOversizedRequestWithApiError() throws Exception {
        var response = upload(new byte[6000]);
        assertEquals(413,response.statusCode(),response.body());
    }
    @Test void realMalformedAndNoTextPdfErrors() throws Exception {
        assertEquals(422,upload("%PDF-garbage".getBytes(StandardCharsets.US_ASCII)).statusCode());
        var response = upload(PdfBoxResumeTextExtractorTest.pdf("",false));
        assertEquals(422,response.statusCode(),response.body());
        assertTrue(response.body().contains("meaningful text"));
    }

    private static String extractionFingerprint(String description) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        put(digest, "description-v1");
        put(digest, description);
        return "jc005-v1:sha256:" + HexFormat.of().formatHex(digest.digest());
    }

    private static void put(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }
}

