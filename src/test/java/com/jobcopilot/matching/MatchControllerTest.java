package com.jobcopilot.matching;
import com.jobcopilot.common.web.ApiErrorHandler;
import com.jobcopilot.job.JobNotFoundException;
import com.jobcopilot.resume.ResumeExceptions.CandidateProfileNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
class MatchControllerTest {
    private final MatchService service = mock(MatchService.class);
    private final org.springframework.test.web.servlet.MockMvc mvc = MockMvcBuilders.standaloneSetup(new MatchController(service))
            .setControllerAdvice(new ApiErrorHandler()).build();
    @Test void rejectsMissingAndNonpositiveIds() throws Exception {
        for (String body : new String[]{"{}","{\"jobId\":0,\"candidateProfileId\":1}","{\"jobId\":1,\"candidateProfileId\":-1}"})
            mvc.perform(post("/api/matches").contentType("application/json").content(body)).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
    @Test void mapsInsufficientRequirements() throws Exception {
        when(service.match(any())).thenThrow(new MatchCannotBeComputedException());
        mvc.perform(post("/api/matches").contentType("application/json").content("{\"jobId\":1,\"candidateProfileId\":1}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.message").exists());
    }
    @Test void mapsMissingResources() throws Exception {
        for (RuntimeException exception : new RuntimeException[]{new JobNotFoundException(9L),new CandidateProfileNotFoundException(9L)}) {
            doThrow(exception).when(service).match(any());
            mvc.perform(post("/api/matches").contentType("application/json").content("{\"jobId\":9,\"candidateProfileId\":9}")).andExpect(status().isNotFound());
        }
    }
}
