package com.jobcopilot.matching;
import com.jobcopilot.job.*;
import com.jobcopilot.resume.*;
import com.jobcopilot.matching.dto.MatchRequest;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
class MatchServiceTest {
    @Test void missingJobStopsBeforeProfileOrEngine() {
        var jobs = mock(JobService.class); var resumes = mock(ResumePersistenceService.class); var engine = mock(DeterministicMatchingEngine.class);
        when(jobs.matchingSnapshot(9L)).thenThrow(new JobNotFoundException(9L));
        assertThrows(JobNotFoundException.class, () -> new MatchService(jobs,resumes,engine).match(new MatchRequest(1L,9L)));
        verifyNoInteractions(resumes,engine);
    }
    @Test void missingProfileStopsBeforeEngine() {
        var jobs = mock(JobService.class); var resumes = mock(ResumePersistenceService.class); var engine = mock(DeterministicMatchingEngine.class);
        when(resumes.matchingSnapshot(9L)).thenThrow(new ResumeExceptions.CandidateProfileNotFoundException(9L));
        assertThrows(ResumeExceptions.CandidateProfileNotFoundException.class, () -> new MatchService(jobs,resumes,engine).match(new MatchRequest(9L,1L)));
        verifyNoInteractions(engine);
    }
}

