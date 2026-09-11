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
        long profileId = resume.path("candidateProfile").path("id").asLong();
        assertEquals(200,request("GET",uploaded.headers().firstValue("Location").orElseThrow(),"").statusCode());
        assertEquals(200,request("GET","/api/candidate-profiles/" + profileId,"").statusCode());
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
}

