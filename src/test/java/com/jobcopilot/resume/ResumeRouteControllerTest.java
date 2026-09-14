package com.jobcopilot.resume;

import com.jobcopilot.common.web.ApiErrorHandler;
import com.jobcopilot.resume.ResumeExceptions.ResumeRouteConflictException;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ResumeRouteControllerTest {
    private final ResumeRouteService service = mock(ResumeRouteService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ResumeRouteController(service))
            .setControllerAdvice(new ApiErrorHandler()).build();

    @Test void duplicateRouteReturnsStableConflictWithoutDatabaseDetails() throws Exception {
        when(service.create(eq(1L), any())).thenThrow(new ResumeRouteConflictException());

        mvc.perform(post("/api/candidate-profiles/1/resume-routes")
                        .contentType("application/json")
                        .content("""
                                {"resumeId":2,"strategy":"VOLUME","defaultRoute":true,
                                 "variantLabel":"Default","approved":true}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value(
                        "A resume route already exists for this profile, strategy, and route selector"));
    }
}
