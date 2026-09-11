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
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static com.jobcopilot.matching.MatchResult.ExperienceComparison;
import static com.jobcopilot.matching.MatchResult.Recommendation;
import static com.jobcopilot.matching.MatchResult.Status;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
    void successReturnsOrderedRanksAndNewCountModel() throws Exception {
        when(service.rank(eq(7L), any(JobRankingQuery.class))).thenReturn(sampleResponse());

        mvc.perform(get("/api/candidate-profiles/7/job-rankings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidateProfileId").value(7))
                .andExpect(jsonPath("$.evaluatedJobCount").value(2))
                .andExpect(jsonPath("$.computableJobCount").value(1))
                .andExpect(jsonPath("$.unassessedJobCount").value(1))
                .andExpect(jsonPath("$.filteredJobCount").value(1))
                .andExpect(jsonPath("$.pageResultCount").value(1))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.first").value(true))
                .andExpect(jsonPath("$.last").value(true))
                .andExpect(jsonPath("$.rankedJobCount").doesNotExist())
                .andExpect(jsonPath("$.filteredRankedJobCount").doesNotExist())
                .andExpect(jsonPath("$.rankedJobs[0].rank").value(1))
                .andExpect(jsonPath("$.rankedJobs[0].job.id").value(3))
                .andExpect(jsonPath("$.rankedJobs[0].match.overallScore").value(80.00))
                .andExpect(jsonPath("$.rankedJobs[0].match.recommendation").value("STRONG_MATCH"))
                .andExpect(jsonPath("$.unassessedJobs[0].job.id").value(4))
                .andExpect(jsonPath("$.unassessedJobs[0].reason").value("INSUFFICIENT_JOB_REQUIREMENTS"))
                .andExpect(jsonPath("$.unassessedJobs[0].rank").doesNotExist())
                .andExpect(jsonPath("$.unassessedJobs[0].match").doesNotExist());

        ArgumentCaptor<JobRankingQuery> captor = ArgumentCaptor.forClass(JobRankingQuery.class);
        verify(service).rank(eq(7L), captor.capture());
        assertEquals(JobRankingQuery.DEFAULT_STATUSES, captor.getValue().statuses());
        assertNull(captor.getValue().minScore());
        assertTrue(captor.getValue().recommendations().isEmpty());
        assertEquals(0, captor.getValue().page());
        assertEquals(20, captor.getValue().size());
    }

    @Test
    void repeatedFiltersAreParsedAsOverrideAndOrSemantics() throws Exception {
        when(service.rank(eq(7L), any(JobRankingQuery.class))).thenReturn(sampleResponse());

        mvc.perform(get("/api/candidate-profiles/7/job-rankings")
                        .queryParam("status", "DISCOVERED")
                        .queryParam("status", "OFFER")
                        .queryParam("recommendation", "STRONG_MATCH")
                        .queryParam("recommendation", "GOOD_MATCH")
                        .queryParam("minScore", "65.00")
                        .queryParam("page", "1")
                        .queryParam("size", "10"))
                .andExpect(status().isOk());

        ArgumentCaptor<JobRankingQuery> captor = ArgumentCaptor.forClass(JobRankingQuery.class);
        verify(service).rank(eq(7L), captor.capture());
        assertEquals(Set.of(JobStatus.DISCOVERED, JobStatus.OFFER), captor.getValue().statuses());
        assertEquals(Set.of(Recommendation.STRONG_MATCH, Recommendation.GOOD_MATCH), captor.getValue().recommendations());
        assertEquals(0, captor.getValue().minScore().compareTo(new BigDecimal("65.00")));
        assertEquals(1, captor.getValue().page());
        assertEquals(10, captor.getValue().size());
    }

    @Test
    void surroundingWhitespaceIsTrimmedThroughTheMvcParameterMap() throws Exception {
        when(service.rank(eq(7L), any(JobRankingQuery.class))).thenReturn(sampleResponse());

        mvc.perform(get("/api/candidate-profiles/7/job-rankings")
                        .queryParam("status", " DISCOVERED ")
                        .queryParam("recommendation", " GOOD_MATCH ")
                        .queryParam("minScore", " 6.5E1 ")
                        .queryParam("page", " 1 ")
                        .queryParam("size", " 10 "))
                .andExpect(status().isOk());

        ArgumentCaptor<JobRankingQuery> captor = ArgumentCaptor.forClass(JobRankingQuery.class);
        verify(service).rank(eq(7L), captor.capture());
        assertEquals(Set.of(JobStatus.DISCOVERED), captor.getValue().statuses());
        assertEquals(Set.of(Recommendation.GOOD_MATCH), captor.getValue().recommendations());
        assertEquals(0, captor.getValue().minScore().compareTo(new BigDecimal("65")));
        assertEquals(1, captor.getValue().page());
        assertEquals(10, captor.getValue().size());
    }

    @Test
    void missingCandidateUsesExistingNotFoundContract() throws Exception {
        when(service.rank(eq(9L), any(JobRankingQuery.class))).thenThrow(new CandidateProfileNotFoundException(9L));

        mvc.perform(get("/api/candidate-profiles/9/job-rankings"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("Candidate profile 9 was not found"));
    }

    @Test
    void noJobsReturnsEmptyArrays() throws Exception {
        when(service.rank(eq(1L), any(JobRankingQuery.class))).thenReturn(JobRankingResponse.of(
                1L, "from-engine", "v1", "rules-v1", LocalDate.of(2026, 9, 7),
                0, 0, 0, 0, 20, List.of(), List.of()));

        mvc.perform(get("/api/candidate-profiles/1/job-rankings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.evaluatedJobCount").value(0))
                .andExpect(jsonPath("$.computableJobCount").value(0))
                .andExpect(jsonPath("$.filteredJobCount").value(0))
                .andExpect(jsonPath("$.pageResultCount").value(0))
                .andExpect(jsonPath("$.totalPages").value(0))
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
        when(service.rank(eq(1L), any(JobRankingQuery.class))).thenReturn(JobRankingResponse.of(
                1L, "from-engine", "v1", "rules-v1", LocalDate.of(2026, 9, 7),
                1, 0, 0, 0, 20, List.of(), List.of(unassessed)));

        mvc.perform(get("/api/candidate-profiles/1/job-rankings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.computableJobCount").value(0))
                .andExpect(jsonPath("$.unassessedJobCount").value(1))
                .andExpect(jsonPath("$.filteredJobCount").value(0));
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
        when(service.rank(eq(0L), any(JobRankingQuery.class))).thenThrow(new CandidateProfileNotFoundException(0L));
        when(service.rank(eq(-1L), any(JobRankingQuery.class))).thenThrow(new CandidateProfileNotFoundException(-1L));
        when(service.rank(eq(Long.MAX_VALUE), any(JobRankingQuery.class)))
                .thenThrow(new CandidateProfileNotFoundException(Long.MAX_VALUE));

        mvc.perform(get("/api/candidate-profiles/0/job-rankings"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/candidate-profiles/-1/job-rankings"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/candidate-profiles/9223372036854775807/job-rankings"))
                .andExpect(status().isNotFound());

        verify(service).rank(eq(0L), any(JobRankingQuery.class));
        verify(service).rank(eq(-1L), any(JobRankingQuery.class));
        verify(service).rank(eq(Long.MAX_VALUE), any(JobRankingQuery.class));
    }

    @Test
    void unexpectedRankingFailureUsesGeneric500() throws Exception {
        when(service.rank(eq(1L), any(JobRankingQuery.class)))
                .thenThrow(new JobRankingComputationException(5L, new IllegalStateException("secret-job-details")));

        mvc.perform(get("/api/candidate-profiles/1/job-rankings"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.error").value("Internal Server Error"))
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"))
                .andExpect(jsonPath("$.message").value(not(containsString("secret"))))
                .andExpect(jsonPath("$.message").value(not(containsString("job 5"))));
    }

    @Test
    void invalidQueryParametersUseExistingBadRequestShape() throws Exception {
        assertBadRequest(get("/api/candidate-profiles/1/job-rankings").queryParam("page", "-1"),
                "page must be at least 0");
        assertBadRequest(get("/api/candidate-profiles/1/job-rankings").queryParam("size", "0"),
                "size must be between 1 and 100");
        assertBadRequest(get("/api/candidate-profiles/1/job-rankings").queryParam("size", "101"),
                "size must be between 1 and 100");
        assertBadRequest(get("/api/candidate-profiles/1/job-rankings").queryParam("minScore", "-1"),
                "minScore must be between 0 and 100");
        assertBadRequest(get("/api/candidate-profiles/1/job-rankings").queryParam("minScore", "101"),
                "minScore must be between 0 and 100");
        assertBadRequest(get("/api/candidate-profiles/1/job-rankings").queryParam("minScore", ""),
                "minScore must not be blank");
        assertBadRequest(get("/api/candidate-profiles/1/job-rankings").queryParam("minScore", "nope"),
                "minScore is malformed");
        assertBadRequest(get("/api/candidate-profiles/1/job-rankings").queryParam("status", ""),
                "status must not be blank");
        assertBadRequest(get("/api/candidate-profiles/1/job-rankings").queryParam("status", "READY"),
                "Invalid value for status");
        assertBadRequest(get("/api/candidate-profiles/1/job-rankings").queryParam("status", "discovered"),
                "Invalid value for status");
        assertBadRequest(get("/api/candidate-profiles/1/job-rankings").queryParam("status", "DISCOVERED,APPLIED"),
                "Invalid value for status");
        assertBadRequest(get("/api/candidate-profiles/1/job-rankings")
                        .queryParam("status", "DISCOVERED").queryParam("status", ""),
                "status must not be blank");
        assertBadRequest(get("/api/candidate-profiles/1/job-rankings")
                        .queryParam("status", "").queryParam("status", "DISCOVERED"),
                "status must not be blank");
        assertBadRequest(get("/api/candidate-profiles/1/job-rankings").queryParam("recommendation", ""),
                "recommendation must not be blank");
        assertBadRequest(get("/api/candidate-profiles/1/job-rankings").queryParam("recommendation", "EXCELLENT"),
                "Invalid value for recommendation");
        assertBadRequest(get("/api/candidate-profiles/1/job-rankings").queryParam("recommendation", "strong_match"),
                "Invalid value for recommendation");
        assertBadRequest(get("/api/candidate-profiles/1/job-rankings")
                        .queryParam("recommendation", "GOOD_MATCH").queryParam("recommendation", ""),
                "recommendation must not be blank");
        assertBadRequest(get("/api/candidate-profiles/1/job-rankings").queryParam("page", "0").queryParam("page", "1"),
                "page must not be specified more than once");
        assertBadRequest(get("/api/candidate-profiles/1/job-rankings").queryParam("size", "10").queryParam("size", "20"),
                "size must not be specified more than once");
        assertBadRequest(get("/api/candidate-profiles/1/job-rankings").queryParam("minScore", "10").queryParam("minScore", "20"),
                "minScore must not be specified more than once");
        assertBadRequest(get("/api/candidate-profiles/1/job-rankings").queryParam("page", "first"),
                "Invalid value for page");
        assertBadRequest(get("/api/candidate-profiles/1/job-rankings").queryParam("size", "big"),
                "Invalid value for size");
        assertBadRequest(get("/api/candidate-profiles/1/job-rankings").queryParam("page", "2147483648"),
                "Invalid value for page");
        verifyNoInteractions(service);
    }

    private void assertBadRequest(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
            String message
    ) throws Exception {
        mvc.perform(request)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value(message));
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
                2,
                1,
                1,
                0,
                20,
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
