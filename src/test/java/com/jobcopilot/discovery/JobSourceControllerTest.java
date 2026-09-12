package com.jobcopilot.discovery;

import com.jobcopilot.common.web.ApiErrorHandler;
import com.jobcopilot.discovery.JobSourceApiExceptions.SourceDisabled;
import com.jobcopilot.discovery.dto.JobSourceResponse;
import com.jobcopilot.discovery.dto.JobSourceSyncRunResponse;
import com.jobcopilot.discovery.lever.LeverRegion;
import java.time.LocalDateTime;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class JobSourceControllerTest {
    private static final LocalValidatorFactoryBean VALIDATOR = validator();
    private JobSourceService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(JobSourceService.class);
        mvc = MockMvcBuilders.standaloneSetup(new JobSourceController(service))
                .setControllerAdvice(new ApiErrorHandler()).setValidator(VALIDATOR).build();
    }

    @AfterAll static void close() { VALIDATOR.close(); }

    @Test
    void createReturns201AndValidatesContract() throws Exception {
        when(service.create(any())).thenReturn(source());
        mvc.perform(post("/api/job-sources").contentType(MediaType.APPLICATION_JSON).content("""
                {"provider":"LEVER","region":"GLOBAL","sourceKey":"Example",
                 "companyName":"Example","enabled":true}
                """))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.sourceKey").value("example"));

        mvc.perform(post("/api/job-sources").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.provider").exists())
                .andExpect(jsonPath("$.fieldErrors.enabled").exists());
    }

    @Test
    void noGeneralSourcePutExists() throws Exception {
        mvc.perform(put("/api/job-sources/1").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void failedRunIsStillHttp200AndDisabledIs409() throws Exception {
        when(service.synchronize(1L)).thenReturn(run(JobSourceSyncStatus.FAILED, "TIMEOUT"));
        mvc.perform(post("/api/job-sources/1/sync"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureCode").value("TIMEOUT"));

        when(service.synchronize(2L)).thenThrow(new SourceDisabled(2L));
        mvc.perform(post("/api/job-sources/2/sync"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("Job source 2 is disabled"));
    }

    @Test
    void malformedEnumsAndPaginationTypesAreAreSafe400s() throws Exception {
        mvc.perform(post("/api/job-sources").contentType(MediaType.APPLICATION_JSON).content("""
                {"provider":"GREENHOUSE","region":"GLOBAL","sourceKey":"x","companyName":"x","enabled":true}
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Request body is malformed or contains an invalid value"));
        mvc.perform(get("/api/job-sources").queryParam("page", "first"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Invalid value for page"));
    }

    private static JobSourceResponse source() {
        LocalDateTime now = LocalDateTime.now();
        return new JobSourceResponse(1L, JobSourceProvider.LEVER, LeverRegion.GLOBAL, "example",
                "Example", true, null, now, now);
    }

    private static JobSourceSyncRunResponse run(JobSourceSyncStatus status, String failure) {
        LocalDateTime now = LocalDateTime.now();
        return new JobSourceSyncRunResponse(1, 1, JobSourceSyncTrigger.MANUAL, status, now, now,
                failure, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    private static LocalValidatorFactoryBean validator() {
        LocalValidatorFactoryBean value = new LocalValidatorFactoryBean();
        value.afterPropertiesSet();
        return value;
    }
}
