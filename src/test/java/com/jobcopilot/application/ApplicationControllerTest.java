package com.jobcopilot.application;

import com.jobcopilot.common.web.ApiErrorHandler;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.math.BigDecimal;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ApplicationControllerTest {
    private final ApplicationDecisionService decisions = mock(ApplicationDecisionService.class);
    private final ApplicationReadinessService readiness = mock(ApplicationReadinessService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ApplicationController(decisions, readiness))
            .setControllerAdvice(new ApiErrorHandler()).build();

    @Test void exposesDecisionAndReadinessThroughJobScopedEndpoints() throws Exception {
        var decision = new ApplicationDecisionResult(9L, 2L, ApplicationDecision.APPLY_VOLUME, List.of("GOOD_MATCH_VOLUME_ROUTE"),
                new BigDecimal("72"), com.jobcopilot.matching.MatchResult.Recommendation.GOOD_MATCH, false,
                ResumeStrategy.VOLUME, ApplicationDecisionPolicy.VERSION);
        when(decisions.decide(9L, 2L)).thenReturn(decision);
        when(readiness.evaluate(9L, 2L)).thenReturn(new ApplicationReadinessResult(9L, 2L,
                ApplicationReadiness.READY, decision, null,
                List.of(new ReadinessCheck("JOB_VERIFIED_LIVE", ReadinessCheck.Status.PASS, "live"))));

        mvc.perform(get("/api/jobs/9/application-decision").param("candidateProfileId", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.decision").value("APPLY_VOLUME"))
                .andExpect(jsonPath("$.policyVersion").value(ApplicationDecisionPolicy.VERSION));
        mvc.perform(get("/api/jobs/9/application-readiness").param("candidateProfileId", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.readiness").value("READY"))
                .andExpect(jsonPath("$.checks[0].code").value("JOB_VERIFIED_LIVE"));
    }
}
