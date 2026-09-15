package com.jobcopilot.application;

import com.jobcopilot.application.dto.LiveOpportunityPageResponse;
import com.jobcopilot.common.web.ApiErrorHandler;
import com.jobcopilot.resume.ResumeExceptions.CandidateProfileNotConfirmedException;
import com.jobcopilot.resume.ResumeExceptions.CandidateProfileNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LiveOpportunityControllerTest {
    private final LiveOpportunityService service = mock(LiveOpportunityService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new LiveOpportunityController(service))
            .setControllerAdvice(new ApiErrorHandler()).build();

    @Test
    void exposesBoundedFiltersAndAuthorityMetadata() throws Exception {
        when(service.find(eq(7L), any())).thenReturn(emptyPage());

        mvc.perform(get("/api/candidate-profiles/7/opportunities")
                        .param("minScore", "65").param("recommendation", "GOOD_MATCH")
                        .param("readiness", "READY").param("page", "0").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidateProfileId").value(7))
                .andExpect(jsonPath("$.profileRevision").value(4))
                .andExpect(jsonPath("$.opportunities").isArray());
        verify(service).find(eq(7L), any(OpportunityQuery.class));
    }

    @Test
    void mapsInvalidMissingAndDraftRequestsToStableErrors() throws Exception {
        mvc.perform(get("/api/candidate-profiles/7/opportunities").param("status", "APPLIED"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/candidate-profiles/7/opportunities").param("readiness", "UNKNOWN"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/candidate-profiles/7/opportunities").param("size", "101"))
                .andExpect(status().isBadRequest());

        when(service.find(eq(404L), any())).thenThrow(new CandidateProfileNotFoundException(404L));
        mvc.perform(get("/api/candidate-profiles/404/opportunities"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.status").value(404));
        when(service.find(eq(409L), any())).thenThrow(new CandidateProfileNotConfirmedException(409L));
        mvc.perform(get("/api/candidate-profiles/409/opportunities"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.status").value(409));
    }

    private static LiveOpportunityPageResponse emptyPage() {
        return new LiveOpportunityPageResponse(7L, 4, "deterministic-v1", "v1", "rules-v1",
                LocalDate.of(2026, 9, 15), null, null, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 20, 0, true, true, List.of(), List.of());
    }
}
