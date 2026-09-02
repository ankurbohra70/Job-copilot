package com.jobcopilot.job;

import com.jobcopilot.common.web.ApiErrorHandler;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class JobControllerTest {

    private static final LocalValidatorFactoryBean VALIDATOR = createValidator();

    private JobService jobService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        jobService = mock(JobService.class);
        JobController controller = new JobController(jobService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
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
    void createJobReturnsCreatedJob() throws Exception {
        LocalDateTime timestamp = LocalDateTime.of(2026, 9, 2, 12, 30);
        JobResponse response = new JobResponse(
                1L,
                "Backend Software Engineer",
                "Example Technologies",
                "Gurugram",
                "https://example.com/jobs/123",
                "Java Spring Boot backend role",
                "MANUAL",
                "123",
                timestamp,
                timestamp
        );
        when(jobService.createJob(any())).thenReturn(response);

        mockMvc.perform(post("/api/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Backend Software Engineer",
                                  "company": "Example Technologies",
                                  "location": "Gurugram",
                                  "jobUrl": "https://example.com/jobs/123",
                                  "description": "Java Spring Boot backend role",
                                  "source": "MANUAL",
                                  "externalJobId": "123"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.title").value("Backend Software Engineer"))
                .andExpect(jsonPath("$.company").value("Example Technologies"));

        verify(jobService).createJob(any());
    }

    @Test
    void createJobRejectsBlankTitle() throws Exception {
        mockMvc.perform(post("/api/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "   ",
                                  "company": "Example Technologies"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors.title").value("must not be blank"));

        verifyNoInteractions(jobService);
    }

    @Test
    void createJobRejectsBlankCompany() throws Exception {
        mockMvc.perform(post("/api/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Backend Software Engineer",
                                  "company": ""
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.company").value("must not be blank"));

        verifyNoInteractions(jobService);
    }

    @Test
    void getJobsReturnsJobResponses() throws Exception {
        LocalDateTime timestamp = LocalDateTime.of(2026, 9, 2, 12, 30);
        when(jobService.getJobs()).thenReturn(List.of(new JobResponse(
                1L,
                "Backend Software Engineer",
                "Example Technologies",
                null,
                null,
                null,
                null,
                null,
                timestamp,
                timestamp
        )));

        mockMvc.perform(get("/api/jobs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].title").value("Backend Software Engineer"));

        verify(jobService).getJobs();
    }
}
