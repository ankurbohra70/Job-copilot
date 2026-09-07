package com.jobcopilot.matching;

import com.jobcopilot.common.web.ApiErrorHandler;
import com.jobcopilot.job.JobStatus;
import com.jobcopilot.matching.dto.JobRankingResponse;
import com.jobcopilot.matching.dto.RankedJobResponse;
import com.jobcopilot.matching.dto.RankingJobSummary;
import com.jobcopilot.matching.dto.RankingMatchSummary;
import com.jobcopilot.matching.dto.UnassessedJobResponse;
import com.jobcopilot.resume.ResumeExceptions.CandidateProfileNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static com.jobcopilot.matching.MatchResult.ExperienceComparison;
import static com.jobcopilot.matching.MatchResult.Recommendation;
import static com.jobcopilot.matching.MatchResult.Status;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class JobRankingControllerTest {
    private final JobRankingService service = mock(JobRankingService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new JobRankingController(service))
            .setControllerAdvice(new ApiErrorHandler())
            .build();

    @Test
    void successReturnsOrderedRanks() throws Exception {
        when(service.rank(7L)).thenReturn(sampleResponse());

        mvc.perform(get("/api/candidate-profiles/7/job-rankings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidateProfileId").value(7))
                .andExpect(jsonPath("$.evaluatedJobCount").value(2))
                .andExpect(jsonPath("$.rankedJobCount").value(1))
                .andExpect(jsonPath("$.unassessedJobCount").value(1))
                .andExpect(jsonPath("$.rankedJobs[0].rank").value(1))
                .andExpect(jsonPath("$.rankedJobs[0].job.id").value(3))
                .andExpect(jsonPath("$.rankedJobs[0].match.overallScore").value(80.00))
                .andExpect(jsonPath("$.rankedJobs[0].match.recommendation").value("STRONG_MATCH"))
                .andExpect(jsonPath("$.unassessedJobs[0].job.id").value(4))
                .andExpect(jsonPath("$.unassessedJobs[0].reason").value("INSUFFICIENT_JOB_REQUIREMENTS"))
                .andExpect(jsonPath("$.unassessedJobs[0].rank").doesNotExist())
                .andExpect(jsonPath("$.unassessedJobs[0].match").doesNotExist());

        verify(service).rank(7L);
    }

    @Test
    void missingCandidateUsesExistingNotFoundContract() throws Exception {
        when(service.rank(9L)).thenThrow(new CandidateProfileNotFoundException(9L));

        mvc.perform(get("/api/candidate-profiles/9/job-rankings"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("Candidate profile 9 was not found"));
    }

    @Test
    void noJobsReturnsEmptyArrays() throws Exception {
        when(service.rank(1L)).thenReturn(JobRankingResponse.of(
                1L, "from-engine", "v1", "rules-v1", LocalDate.of(2026, 9, 7), List.of(), List.of()));

        mvc.perform(get("/api/candidate-profiles/1/job-rankings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.evaluatedJobCount").value(0))
                .andExpect(jsonPath("$.rankedJobs").isEmpty())
                .andExpect(jsonPath("$.unassessedJobs").isEmpty());
    }

    @Test
    void allUnassessedReturns200() throws Exception {
        var unassessed = new UnassessedJobResponse(
                summary(4L, LocalDateTime.of(2026, 9, 7, 0, 0)),
                UnassessedJobResponse.Reason.INSUFFICIENT_JOB_REQUIREMENTS,
                new MatchCannotBeComputedException().getMessage()
        );
        when(service.rank(1L)).thenReturn(JobRankingResponse.of(
                1L, "from-engine", "v1", "rules-v1", LocalDate.of(2026, 9, 7), List.of(), List.of(unassessed)));

        mvc.perform(get("/api/candidate-profiles/1/job-rankings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rankedJobCount").value(0))
                .andExpect(jsonPath("$.unassessedJobCount").value(1));
    }

    @Test
    void invalidPathIdIsBadRequest() throws Exception {
        mvc.perform(get("/api/candidate-profiles/abc/job-rankings"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid value for candidateProfileId"));
        verifyNoInteractions(service);
    }

    @Test
    void numericOverflowPathIdIsBadRequest() throws Exception {
        mvc.perform(get("/api/candidate-profiles/9223372036854775808/job-rankings"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid value for candidateProfileId"));
        verifyNoInteractions(service);
    }

    @Test
    void zeroNegativeAndMaximumLongIdsFollowExistingNotFoundConvention() throws Exception {
        when(service.rank(0L)).thenThrow(new CandidateProfileNotFoundException(0L));
        when(service.rank(-1L)).thenThrow(new CandidateProfileNotFoundException(-1L));
        when(service.rank(Long.MAX_VALUE)).thenThrow(new CandidateProfileNotFoundException(Long.MAX_VALUE));

        mvc.perform(get("/api/candidate-profiles/0/job-rankings"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/candidate-profiles/-1/job-rankings"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/candidate-profiles/9223372036854775807/job-rankings"))
                .andExpect(status().isNotFound());

        verify(service).rank(0L);
        verify(service).rank(-1L);
        verify(service).rank(Long.MAX_VALUE);
    }

    @Test
    void unexpectedRankingFailureUsesGeneric500() throws Exception {
        when(service.rank(1L)).thenThrow(new JobRankingComputationException(5L, new IllegalStateException("secret-job-details")));

        mvc.perform(get("/api/candidate-profiles/1/job-rankings"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.error").value("Internal Server Error"))
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"))
                .andExpect(jsonPath("$.message").value(not(containsString("secret"))))
                .andExpect(jsonPath("$.message").value(not(containsString("job 5"))));
    }

    private static JobRankingResponse sampleResponse() {
        var ranked = new RankedJobResponse(
                1,
                summary(3L, LocalDateTime.of(2026, 9, 8, 0, 0)),
                new RankingMatchSummary(
                        new BigDecimal("80.00"),
                        Recommendation.STRONG_MATCH,
                        List.of("java"),
                        List.of(),
                        List.of(),
                        List.of(),
                        new ExperienceComparison(null, null, Status.NOT_APPLICABLE, null),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of("LOCATION_WORK_MODE")
                )
        );
        var unassessed = new UnassessedJobResponse(
                summary(4L, LocalDateTime.of(2026, 9, 7, 0, 0)),
                UnassessedJobResponse.Reason.INSUFFICIENT_JOB_REQUIREMENTS,
                new MatchCannotBeComputedException().getMessage()
        );
        return JobRankingResponse.of(
                7L,
                "from-engine",
                "v1",
                "rules-v1",
                LocalDate.of(2026, 9, 7),
                List.of(ranked),
                List.of(unassessed)
        );
    }

    private static RankingJobSummary summary(Long id, LocalDateTime createdAt) {
        return new RankingJobSummary(
                id,
                "Role " + id,
                "Example",
                "Remote",
                "https://example.com/jobs/" + id,
                JobStatus.DISCOVERED,
                createdAt,
                createdAt
        );
    }
}
