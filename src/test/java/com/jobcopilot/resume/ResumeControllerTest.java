package com.jobcopilot.resume;
import com.jobcopilot.common.web.ApiErrorHandler;
import com.jobcopilot.resume.dto.ResumeResponse;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.time.LocalDateTime;
import java.util.stream.Stream;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static com.jobcopilot.resume.ResumeExceptions.*;
class ResumeControllerTest {
    private final ResumeService service = mock(ResumeService.class);
    private final ResumePersistenceService persistence = mock(ResumePersistenceService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ResumeController(service,persistence),new CandidateProfileController(persistence))
            .setControllerAdvice(new ApiErrorHandler()).build();
    @Test void uploadReturnsLocation() throws Exception {
        when(service.upload(any())).thenReturn(new ResumeResponse(1L,"r.pdf",100,1,"test",LocalDateTime.now(),null));
        mvc.perform(multipart("/api/resumes").file(new MockMultipartFile("file","r.pdf","application/pdf","%PDF-".getBytes())))
                .andExpect(status().isCreated()).andExpect(header().string("Location","/api/resumes/1"));
    }
    @Test void missingPartIsBadRequest() throws Exception { mvc.perform(multipart("/api/resumes")).andExpect(status().isBadRequest()); verifyNoInteractions(service); }
    @Test void rejectsMultipleFiles() throws Exception {
        mvc.perform(multipart("/api/resumes").file(new MockMultipartFile("file","one".getBytes()))
                .file(new MockMultipartFile("file","two".getBytes()))).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
    @Test void notFoundResponses() throws Exception {
        when(persistence.getResume(9L)).thenThrow(new ResumeNotFoundException(9L));
        when(persistence.getProfile(9L)).thenThrow(new CandidateProfileNotFoundException(9L));
        mvc.perform(get("/api/resumes/9")).andExpect(status().isNotFound());
        mvc.perform(get("/api/candidate-profiles/9")).andExpect(status().isNotFound());
    }
    static Stream<Object[]> errors() {
        return Stream.of(new Object[]{new InvalidResumeFileException("empty"),400},
                new Object[]{new UnsupportedResumeTypeException(),415},new Object[]{new ResumeTooLargeException(),413},
                new Object[]{new ResumeParsingException("unreadable"),422},new Object[]{new EmptyResumeTextException(),422},
                new Object[]{new ResumeProcessingLimitException("limit"),422});
    }
    @ParameterizedTest @MethodSource("errors") void mapsDomainErrors(RuntimeException exception, int expected) throws Exception {
        when(service.upload(any())).thenThrow(exception);
        mvc.perform(multipart("/api/resumes").file(new MockMultipartFile("file","x".getBytes())))
                .andExpect(status().is(expected)).andExpect(jsonPath("$.status").value(expected)).andExpect(jsonPath("$.message").exists());
    }
}
