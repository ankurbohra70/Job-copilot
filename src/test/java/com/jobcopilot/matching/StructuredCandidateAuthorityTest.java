package com.jobcopilot.matching;

import com.jobcopilot.job.JobMatchingSnapshot;
import com.jobcopilot.job.dto.JobRequirementsResponse;
import com.jobcopilot.resume.CandidateMatchingSnapshot;
import com.jobcopilot.resume.CandidateProfileData;
import com.jobcopilot.resume.DeterministicProfileParser;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StructuredCandidateAuthorityTest {
    private final DeterministicMatchingEngine engine = new DeterministicMatchingEngine();

    @Test void matchingContractContainsNoRawOrSyntheticTextAuthority() {
        assertEquals(List.of("id", "profile", "parserVersion", "vocabularyVersion", "assessedOn", "revision"),
                Arrays.stream(CandidateMatchingSnapshot.class.getRecordComponents()).map(component -> component.getName()).toList());
        assertTrue(Arrays.stream(CandidateMatchingSnapshot.class.getRecordComponents())
                .noneMatch(component -> component.getName().toLowerCase().contains("text")));
    }

    @Test void releasedGoldenScoreIsPreservedForEquivalentStructuredExtraction() {
        LocalDate date = LocalDate.of(2026, 9, 7);
        CandidateProfileData parsed = new DeterministicProfileParser().parse("Java", date);
        CandidateProfileData authoritative = new CandidateProfileData(parsed.skills(), 27, 27,
                CandidateProfileData.Assessment.KNOWN, parsed.workExperience(), parsed.education(),
                parsed.projects(), parsed.keywords(), parsed.roleCategories(), parsed.evidence(), parsed.warnings());
        var candidate = new CandidateMatchingSnapshot(2L, authoritative, "rules-v1", "v1", date, 2);
        var job = new JobMatchingSnapshot(1L, "Role", null, null,
                new JobRequirementsResponse(List.of("java", "spring-boot"), List.of(), new BigDecimal("3")),
                LocalDateTime.of(2026, 9, 7, 0, 0));

        MatchResult result = engine.match(job, candidate);

        assertEquals(new BigDecimal("58.33"), result.overallScore());
        assertEquals(MatchResult.Recommendation.WEAK_MATCH, result.recommendation());
        assertEquals(List.of("java"), result.matchedRequiredSkills());
        assertEquals(List.of("spring-boot"), result.missingRequiredSkills());
    }

    @Test void provenanceEvidenceCannotRestoreADeletedStructuredFact() {
        CandidateProfileData data = new CandidateProfileData(List.of(), null, 0,
                CandidateProfileData.Assessment.UNKNOWN, List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(new CandidateProfileData.Evidence("SKILL", "java",
                        "Immutable resume said Java")), List.of());
        var candidate = new CandidateMatchingSnapshot(2L, data, "rules-v1", "v1",
                LocalDate.of(2026, 9, 7), 2);
        var job = new JobMatchingSnapshot(1L, "Role", null, null,
                new JobRequirementsResponse(List.of("java"), List.of(), null), LocalDateTime.MIN);

        MatchResult result = engine.match(job, candidate);

        assertTrue(result.matchedRequiredSkills().isEmpty());
        assertEquals(List.of("java"), result.missingRequiredSkills());
        assertEquals(new BigDecimal("0.00"), result.overallScore());
    }
}
