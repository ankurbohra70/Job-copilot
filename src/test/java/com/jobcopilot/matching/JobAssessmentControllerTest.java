package com.jobcopilot.matching;

import com.jobcopilot.common.text.MatchingVocabulary;
import com.jobcopilot.common.web.ApiErrorHandler;
import com.jobcopilot.job.JobMatchingSnapshot;
import com.jobcopilot.job.JobNotFoundException;
import com.jobcopilot.job.dto.JobRequirementsResponse;
import com.jobcopilot.matching.dto.MatchResponse;
import com.jobcopilot.resume.CandidateMatchingSnapshot;
import com.jobcopilot.resume.CandidateProfileData;
import com.jobcopilot.resume.DeterministicProfileParser;
import com.jobcopilot.resume.ResumeExceptions.CandidateProfileNotFoundException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class JobAssessmentControllerTest {
    private static final LocalValidatorFactoryBean VALIDATOR = validator();
    private final JobAssessmentService service = mock(JobAssessmentService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new JobAssessmentController(service))
            .setControllerAdvice(new ApiErrorHandler())
            .setValidator(VALIDATOR)
            .build();

    @AfterAll
    static void closeValidator() {
        VALIDATOR.close();
    }

    @Test
    void successReturnsExistingMatchResponseShape() throws Exception {
        when(service.assess(eq(5L), eq(7L))).thenReturn(sample());

        mvc.perform(post("/api/jobs/5/assessment").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"candidateProfileId\":7}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidateProfileId").value(7))
                .andExpect(jsonPath("$.jobId").value(5))
                .andExpect(jsonPath("$.algorithmVersion").value("deterministic-v1"))
                .andExpect(jsonPath("$.overallScore").value(80.00))
                .andExpect(jsonPath("$.recommendation").value("STRONG_MATCH"))
                .andExpect(jsonPath("$.matchedRequiredSkills[0]").value("java"))
                .andExpect(jsonPath("$.missingRequiredSkills[0]").value("redis"))
                .andExpect(jsonPath("$.matchedPreferredSkills[0]").value("postgresql"))
                .andExpect(jsonPath("$.unmatchedPreferredSkills[0]").value("docker"))
                .andExpect(jsonPath("$.experienceComparison.status").value("MEETS_REQUIREMENT"))
                .andExpect(jsonPath("$.roleRelevance.status").value("ASSESSED"))
                .andExpect(jsonPath("$.keywordRelevance.matchedTerms[0]").value("payments"))
                .andExpect(jsonPath("$.warnings[0]").value("limited-evidence"))
                .andExpect(jsonPath("$.unassessedFactors[0]").value("location"));

        verify(service).assess(5L, 7L);
    }

    @Test
    void rejectsMissingMalformedAndNonpositiveCandidateProfileId() throws Exception {
        mvc.perform(post("/api/jobs/1/assessment").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/jobs/1/assessment").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest());
        for (String body : new String[]{
                "{}",
                "{\"candidateProfileId\":null}",
                "{\"candidateProfileId\":0}",
                "{\"candidateProfileId\":-1}"
        }) {
            mvc.perform(post("/api/jobs/1/assessment").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service);
    }

    @Test
    void nonNumericAndOverflowingJobIdsAreBadRequest() throws Exception {
        mvc.perform(post("/api/jobs/abc/assessment").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"candidateProfileId\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid value for jobId"));
        mvc.perform(post("/api/jobs/9223372036854775808/assessment").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"candidateProfileId\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid value for jobId"));
        verifyNoInteractions(service);
    }

    @Test
    void missingJobAndCandidateUseExistingNotFoundContract() throws Exception {
        when(service.assess(9L, 1L)).thenThrow(new JobNotFoundException(9L));
        mvc.perform(post("/api/jobs/9/assessment").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"candidateProfileId\":1}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Job 9 was not found"));

        when(service.assess(1L, 9L)).thenThrow(new CandidateProfileNotFoundException(9L));
        mvc.perform(post("/api/jobs/1/assessment").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"candidateProfileId\":9}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Candidate profile 9 was not found"));
    }

    @Test
    void uncomputableMatchingIsUnprocessable() throws Exception {
        when(service.assess(1L, 1L)).thenThrow(new MatchCannotBeComputedException());
        mvc.perform(post("/api/jobs/1/assessment").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"candidateProfileId\":1}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void unsupportedMediaTypeUsesExistingContract() throws Exception {
        mvc.perform(post("/api/jobs/1/assessment").contentType(MediaType.TEXT_PLAIN).content("candidateProfileId=1"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.message").value("Unsupported request content type"));
        verifyNoInteractions(service);
    }

    @Test
    void unexpectedFailureIsNotMappedByAssessmentSpecificHandling() {
        when(service.assess(any(), any())).thenThrow(new IllegalStateException("secret-engine-detail"));
        Exception thrown = assertThrows(Exception.class, () ->
                mvc.perform(post("/api/jobs/1/assessment").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"candidateProfileId\":1}")).andReturn());
        assertTrue(String.valueOf(thrown.getMessage()).contains("secret-engine-detail")
                || thrown.getCause() != null && String.valueOf(thrown.getCause().getMessage()).contains("secret-engine-detail"));
    }

    private static MatchResponse sample() {
        var engine = new DeterministicMatchingEngine();
        var job = new JobMatchingSnapshot(
                5L,
                "Backend Engineer",
                "payments systems",
                "Remote",
                new JobRequirementsResponse(List.of("java", "redis"), List.of("postgresql", "docker"), new BigDecimal("1")),
                LocalDateTime.of(2026, 9, 7, 0, 0)
        );
        var parsed = new DeterministicProfileParser().parse(
                "Experience\nBackend Engineer at Acme\n2020-01 - 2023-01\nJava payments systems\nSkills\nJava PostgreSQL",
                LocalDate.of(2026, 9, 7)
        );
        var candidate = new CandidateMatchingSnapshot(
                7L,
                new CandidateProfileData(
                        parsed.skills(), 36, 36, CandidateProfileData.Assessment.KNOWN,
                        parsed.workExperience(), parsed.education(), parsed.projects(),
                        parsed.keywords(), parsed.roleCategories(), parsed.evidence(), parsed.warnings()
                ),
                "rules-v1",
                MatchingVocabulary.standard().version(),
                LocalDate.of(2026, 9, 7),
                2
        );
        var computed = engine.match(job, candidate);
        return new MatchResponse(
                7L, 5L, engine.version(), MatchingVocabulary.standard().version(), "rules-v1",
                LocalDate.of(2026, 9, 7), LocalDateTime.of(2026, 9, 7, 0, 0),
                new BigDecimal("80.00"), MatchResult.Recommendation.STRONG_MATCH,
                List.of("java"), List.of("redis"), List.of("postgresql"), List.of("docker"),
                computed.experienceComparison(),
                computed.roleRelevance(),
                computed.keywordRelevance(),
                computed.breakdown(),
                computed.appliedCaps(),
                computed.strengths(),
                computed.gaps(),
                List.of("limited-evidence"),
                List.of("location")
        );
    }

    private static LocalValidatorFactoryBean validator() {
        var validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        return validator;
    }
}
