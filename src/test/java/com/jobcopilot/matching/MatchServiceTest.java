package com.jobcopilot.matching;

import com.jobcopilot.job.JobNotFoundException;
import com.jobcopilot.matching.dto.MatchRequest;
import com.jobcopilot.matching.dto.MatchResponse;
import com.jobcopilot.resume.ResumeExceptions;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static com.jobcopilot.matching.MatchResult.Recommendation;
import static com.jobcopilot.matching.MatchResult.Status;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class MatchServiceTest {
    private final JobAssessmentService assessments = mock(JobAssessmentService.class);
    private final MatchService service = new MatchService(assessments);

    @Test
    void delegatesJobThenCandidateIdsOnceAndReturnsTheSameResponse() {
        MatchResponse expected = sample();
        when(assessments.assess(8L, 3L)).thenReturn(expected);

        assertSame(expected, service.match(new MatchRequest(3L, 8L)));
        verify(assessments).assess(8L, 3L);
        verifyNoMoreInteractions(assessments);
    }

    @Test
    void missingJobPropagates() {
        JobNotFoundException failure = new JobNotFoundException(9L);
        when(assessments.assess(9L, 1L)).thenThrow(failure);
        assertSame(failure, assertThrows(JobNotFoundException.class, () -> service.match(new MatchRequest(1L, 9L))));
    }

    @Test
    void missingProfilePropagates() {
        var failure = new ResumeExceptions.CandidateProfileNotFoundException(9L);
        when(assessments.assess(1L, 9L)).thenThrow(failure);
        assertSame(failure, assertThrows(ResumeExceptions.CandidateProfileNotFoundException.class,
                () -> service.match(new MatchRequest(9L, 1L))));
    }

    @Test
    void uncomputableMatchingPropagates() {
        MatchCannotBeComputedException failure = new MatchCannotBeComputedException();
        when(assessments.assess(1L, 1L)).thenThrow(failure);
        assertSame(failure, assertThrows(MatchCannotBeComputedException.class, () -> service.match(new MatchRequest(1L, 1L))));
    }

    private static MatchResponse sample() {
        return new MatchResponse(
                3L, 8L, "v1", "v1", "rules-v1",
                LocalDate.of(2026, 9, 7), LocalDateTime.of(2026, 9, 7, 0, 0),
                new BigDecimal("80.00"), Recommendation.STRONG_MATCH,
                List.of("java"), List.of(), List.of(), List.of(),
                new MatchResult.ExperienceComparison(null, null, Status.NOT_APPLICABLE, new BigDecimal("0.00")),
                new MatchResult.Relevance(Status.NOT_APPLICABLE, new BigDecimal("0.00"), List.of()),
                new MatchResult.Relevance(Status.NOT_APPLICABLE, new BigDecimal("0.00"), List.of()),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of()
        );
    }
}
