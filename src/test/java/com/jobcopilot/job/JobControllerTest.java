package com.jobcopilot.job;

import com.jobcopilot.common.web.ApiErrorHandler;
import com.jobcopilot.job.dto.JobPageResponse;
import com.jobcopilot.job.dto.JobResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class JobControllerTest {

    private static final LocalValidatorFactoryBean VALIDATOR = createValidator();
    private static final String VALID_JOB_JSON = """
            {
              "title": "Backend Software Engineer",
              "company": "Example Technologies",
              "location": "Gurugram",
              "jobUrl": "https://example.com/jobs/123",
              "description": "Java Spring Boot backend role",
              "source": "MANUAL",
              "externalJobId": "123"
            }
            """;

    private JobService jobService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        jobService = mock(JobService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new JobController(jobService))
                .setControllerAdvice(new ApiErrorHandler())
                .setValidator(VALIDATOR)
                .build();
    }

    @AfterAll
    static void closeValidatorFactory() {
        VALIDATOR.close();
    }

    private static LocalValidatorFactoryBean createValidator() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        return validator;
    }

    @Test
    void createJobReturnsCreatedJobWithDiscoveredStatus() throws Exception {
        when(jobService.createJob(any())).thenReturn(jobResponse(JobStatus.DISCOVERED));

        mockMvc.perform(post("/api/jobs").contentType(MediaType.APPLICATION_JSON).content(VALID_JOB_JSON))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.title").value("Backend Software Engineer"))
                .andExpect(jsonPath("$.company").value("Example Technologies"))
                .andExpect(jsonPath("$.status").value("DISCOVERED"));

        verify(jobService).createJob(any());
    }

    @Test
    void createJobRejectsBlankTitle() throws Exception {
        assertInvalidBody(post("/api/jobs"), """
                {"title":"   ","company":"Example Technologies"}
                """, "title", "must not be blank");
    }

    @Test
    void createJobRejectsBlankCompany() throws Exception {
        assertInvalidBody(post("/api/jobs"), """
                {"title":"Backend Engineer","company":""}
                """, "company", "must not be blank");
    }

    @Test
    void getExistingJobReturnsResponse() throws Exception {
        when(jobService.getJob(1L)).thenReturn(jobResponse(JobStatus.SHORTLISTED));

        mockMvc.perform(get("/api/jobs/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.status").value("SHORTLISTED"))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.updatedAt").exists());
    }

    @Test
    void getMissingJobReturnsNotFound() throws Exception {
        when(jobService.getJob(99L)).thenThrow(new JobNotFoundException(99L));

        mockMvc.perform(get("/api/jobs/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Job 99 was not found"));
    }

    @Test
    void getNonNumericJobIdReturnsBadRequest() throws Exception {
        mockMvc.perform(get("/api/jobs/not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid value for id"));

        verifyNoInteractions(jobService);
    }

    @Test
    void updateJobReturnsUpdatedResponse() throws Exception {
        when(jobService.updateJob(any(), any())).thenReturn(jobResponse(JobStatus.APPLIED));

        mockMvc.perform(put("/api/jobs/1").contentType(MediaType.APPLICATION_JSON).content(VALID_JOB_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.status").value("APPLIED"));

        verify(jobService).updateJob(any(), any());
    }

    @Test
    void updateJobRejectsBlankTitle() throws Exception {
        assertInvalidBody(put("/api/jobs/1"), """
                {"title":"","company":"Example Technologies"}
                """, "title", "must not be blank");
    }

    @Test
    void updateJobRejectsBlankCompany() throws Exception {
        assertInvalidBody(put("/api/jobs/1"), """
                {"title":"Backend Engineer","company":"   "}
                """, "company", "must not be blank");
    }

    @Test
    void updateJobRejectsOversizedJobUrl() throws Exception {
        String body = """
                {"title":"Backend Engineer","company":"Example Technologies","jobUrl":"%s"}
                """.formatted("x".repeat(2049));
        assertInvalidBody(put("/api/jobs/1"), body, "jobUrl", "must not exceed 2048 characters");
    }

    @Test
    void updateMissingJobReturnsNotFound() throws Exception {
        when(jobService.updateJob(any(), any())).thenThrow(new JobNotFoundException(99L));

        mockMvc.perform(put("/api/jobs/99").contentType(MediaType.APPLICATION_JSON).content(VALID_JOB_JSON))
                .andExpect(status().isNotFound());
    }

    @Test
    void updateStatusReturnsUpdatedJob() throws Exception {
        when(jobService.updateJobStatus(any(), any())).thenReturn(jobResponse(JobStatus.INTERVIEWING));

        mockMvc.perform(patch("/api/jobs/1/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"INTERVIEWING\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INTERVIEWING"));
    }

    @Test
    void updateStatusRejectsMissingStatus() throws Exception {
        assertInvalidBody(patch("/api/jobs/1/status"), "{}", "status", "must not be null");
    }

    @Test
    void updateStatusRejectsNullStatus() throws Exception {
        assertInvalidBody(patch("/api/jobs/1/status"), "{\"status\":null}", "status", "must not be null");
    }

    @Test
    void updateStatusRejectsInvalidEnum() throws Exception {
        mockMvc.perform(patch("/api/jobs/1/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"READY_TO_APPLY\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Request body is malformed or contains an invalid value"));

        verifyNoInteractions(jobService);
    }

    @Test
    void updateStatusForMissingJobReturnsNotFound() throws Exception {
        when(jobService.updateJobStatus(any(), any())).thenThrow(new JobNotFoundException(99L));

        mockMvc.perform(patch("/api/jobs/99/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"APPLIED\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteExistingJobReturnsNoContentWithEmptyBody() throws Exception {
        mockMvc.perform(delete("/api/jobs/1"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        verify(jobService).deleteJob(1L);
    }

    @Test
    void deleteMissingJobReturnsNotFound() throws Exception {
        org.mockito.Mockito.doThrow(new JobNotFoundException(99L)).when(jobService).deleteJob(99L);

        mockMvc.perform(delete("/api/jobs/99"))
                .andExpect(status().isNotFound());
    }

    @Test
    void listJobsUsesDefaultsAndReturnsPaginationMetadata() throws Exception {
        when(jobService.getJobs(0, 20, null, null, null)).thenReturn(jobPage());

        mockMvc.perform(get("/api/jobs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].status").value("DISCOVERED"))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.first").value(true))
                .andExpect(jsonPath("$.last").value(true));

        verify(jobService).getJobs(0, 20, null, null, null);
    }

    @Test
    void listJobsAcceptsExplicitPageSizeSearchStatusAndSort() throws Exception {
        when(jobService.getJobs(2, 10, "company,asc", " backend ", JobStatus.SHORTLISTED))
                .thenReturn(jobPage());

        mockMvc.perform(get("/api/jobs")
                        .queryParam("page", "2")
                        .queryParam("size", "10")
                        .queryParam("sort", "company,asc")
                        .queryParam("q", " backend ")
                        .queryParam("status", "SHORTLISTED"))
                .andExpect(status().isOk());

        verify(jobService).getJobs(2, 10, "company,asc", " backend ", JobStatus.SHORTLISTED);
    }

    @Test
    void listJobsRejectsNegativePage() throws Exception {
        assertInvalidListQuery("page", "-1", "page must be at least 0");
    }

    @Test
    void listJobsRejectsZeroSize() throws Exception {
        assertInvalidListQuery("size", "0", "size must be between 1 and 100");
    }

    @Test
    void listJobsRejectsSizeAboveMaximum() throws Exception {
        assertInvalidListQuery("size", "101", "size must be between 1 and 100");
    }

    @Test
    void listJobsRejectsNonNumericPagination() throws Exception {
        mockMvc.perform(get("/api/jobs").queryParam("page", "first"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid value for page"));

        verifyNoInteractions(jobService);
    }

    @Test
    void listJobsRejectsInvalidStatus() throws Exception {
        mockMvc.perform(get("/api/jobs").queryParam("status", "READY_TO_APPLY"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(jobService);
    }

    @Test
    void listJobsRejectsInvalidSortField() throws Exception {
        assertInvalidListQuery("sort", "description,asc", "unsupported sort field: description");
    }

    @Test
    void listJobsRejectsInvalidSortDirection() throws Exception {
        assertInvalidListQuery("sort", "title,sideways", "sort direction must be asc or desc");
    }

    @Test
    void listJobsRejectsMalformedSort() throws Exception {
        assertInvalidListQuery("sort", "title", "sort must use the format field,direction");
    }

    @Test
    void listJobsRejectsExplicitEmptySort() throws Exception {
        assertInvalidListQuery("sort", "", "sort must use the format field,direction");
    }

    @Test
    void listJobsRejectsWhitespaceOnlySort() throws Exception {
        assertInvalidListQuery("sort", "   ", "sort must use the format field,direction");
    }

    @Test
    void listJobsAcceptsUppercaseSortDirection() throws Exception {
        when(jobService.getJobs(0, 20, "company,DESC", null, null)).thenReturn(jobPage());

        mockMvc.perform(get("/api/jobs").queryParam("sort", "company,DESC"))
                .andExpect(status().isOk());

        verify(jobService).getJobs(0, 20, "company,DESC", null, null);
    }

    private void assertInvalidBody(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
            String body,
            String field,
            String message
    ) throws Exception {
        mockMvc.perform(request.contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors." + field).value(message));
        verifyNoInteractions(jobService);
    }

    private void assertInvalidListQuery(String parameter, String value, String message) throws Exception {
        when(jobService.getJobs(anyInt(), anyInt(), nullable(String.class), isNull(), isNull()))
                .thenThrow(new InvalidJobQueryException(message));

        mockMvc.perform(get("/api/jobs").queryParam(parameter, value))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(message));
    }

    private static JobResponse jobResponse(JobStatus status) {
        LocalDateTime createdAt = LocalDateTime.of(2026, 9, 3, 10, 30);
        return new JobResponse(
                1L,
                "Backend Software Engineer",
                "Example Technologies",
                "Gurugram",
                "https://example.com/jobs/123",
                "Java Spring Boot backend role",
                "MANUAL",
                "123",
                status,
                createdAt,
                createdAt.plusMinutes(1)
        );
    }

    private static JobPageResponse jobPage() {
        return new JobPageResponse(List.of(jobResponse(JobStatus.DISCOVERED)), 0, 20, 1, 1, true, true);
    }
}
