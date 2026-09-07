package com.jobcopilot.matching;
import com.jobcopilot.job.*;
import com.jobcopilot.job.dto.*;
import com.jobcopilot.resume.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static com.jobcopilot.matching.MatchResult.*;

class DeterministicMatchingEngineTest {
    private final DeterministicMatchingEngine engine = new DeterministicMatchingEngine();
    private JobMatchingSnapshot job(List<String> required, List<String> preferred, String minimum) {
        return new JobMatchingSnapshot(1L, "Role", null, null,
                new JobRequirementsResponse(required,preferred,minimum == null ? null : new BigDecimal(minimum)), LocalDateTime.of(2026,9,7,0,0));
    }
    private CandidateMatchingSnapshot candidate(String text, Integer months) {
        var parsed = new DeterministicProfileParser().parse(text, LocalDate.of(2026,9,7));
        var data = new CandidateProfileData(parsed.skills(), months, months,
                months == null ? CandidateProfileData.Assessment.UNKNOWN : CandidateProfileData.Assessment.KNOWN,
                parsed.workExperience(), parsed.education(), parsed.projects(), parsed.keywords(), parsed.roleCategories(), parsed.evidence(), parsed.warnings());
        return new CandidateMatchingSnapshot(2L,data,text,"rules-v1","v1",LocalDate.of(2026,9,7));
    }
    @Test void computesDocumentedWeightedExample() {
        var r = engine.match(job(List.of("java","spring-boot"),List.of(),"3"),candidate("Java",27));
        assertEquals(new BigDecimal("58.33"),r.overallScore()); assertEquals(Recommendation.WEAK_MATCH,r.recommendation());
        assertEquals(List.of("spring-boot"), r.missingRequiredSkills());
    }
    @Test void preferredNeverOutweighRequired() {
        var j = job(List.of("java"),List.of("python"),null);
        assertEquals(new BigDecimal("80.00"),engine.match(j,candidate("Java",null)).overallScore());
        assertEquals(new BigDecimal("20.00"),engine.match(j,candidate("Python",null)).overallScore());
    }
    @Test void onlyPreferredUsesFullSkillWeight() {
        assertEquals(new BigDecimal("100.00"),engine.match(job(List.of(),List.of("java"),null),candidate("Java",null)).overallScore());
    }
    @Test void allMissingRequiredCapsEvenWithFullExperienceCredit() {
        var r = engine.match(job(List.of("java"),List.of(),"1"),candidate("Python",120));
        assertEquals(2,r.appliedCaps().size()); assertTrue(r.overallScore().compareTo(new BigDecimal("49")) <= 0);
    }
    @Test void unknownExperienceKeepsItsWeight() {
        var r = engine.match(job(List.of("java"),List.of(),"3"),candidate("Java",null));
        assertEquals(new BigDecimal("66.67"),r.overallScore()); assertEquals(Status.UNKNOWN,r.experienceComparison().status());
        assertNull(r.experienceComparison().candidateYears());
    }
    @Test void knownZeroIsNotUnknown() {
        assertEquals(Status.BELOW_REQUIREMENT,engine.match(job(List.of(),List.of(),"3"),candidate("Java",0)).experienceComparison().status());
    }
    @ParameterizedTest @CsvSource({"18,50.00","36,100.00","48,100.00"})
    void experienceRatio(int months, String score) {
        assertEquals(new BigDecimal(score),engine.match(job(List.of(),List.of(),"3"),candidate("Java",months)).overallScore());
    }
    @ParameterizedTest @CsvSource({"80,STRONG_MATCH","79.99,GOOD_MATCH","65,GOOD_MATCH","64.99,WEAK_MATCH","45,WEAK_MATCH","44.99,NOT_RECOMMENDED"})
    void recommendationBoundaries(String value, Recommendation recommendation) { assertEquals(recommendation,MatchingPolicy.v1().recommendation(new BigDecimal(value))); }
    @Test void insufficientRequirementsFail() { assertThrows(MatchCannotBeComputedException.class, () -> engine.match(job(List.of(),List.of(),null),candidate("Java",null))); }
    @Test void aliasesAndDuplicatesCannotInflateScore() {
        var result = engine.match(job(List.of("Postgres","postgresql"),List.of("postgres"),null),candidate("Postgres",null));
        assertEquals(List.of("postgresql"),result.matchedRequiredSkills()); assertTrue(result.matchedPreferredSkills().isEmpty());
    }
    @Test void unknownSkillUsesLiteralEvidence() {
        var result = engine.match(job(List.of("custom-tool"),List.of(),null),candidate("Built with custom-tool",null));
        assertEquals(new BigDecimal("100.00"),result.overallScore()); assertFalse(result.strengths().getFirst().evidence().isEmpty());
    }
    @Test void roleAndKeywordsAreExplainableAndRepetitionDoesNotHelp() {
        var j = new JobMatchingSnapshot(1L,"Backend Engineer","Java payments payments systems",null,
                new JobRequirementsResponse(List.of("java"),List.of(),null),LocalDateTime.MIN);
        var c = candidate("Experience\nBackend Engineer at Acme\nJava payments systems",null);
        var r = engine.match(j,c);
        assertEquals(List.of("backend"),r.roleRelevance().matchedTerms());
        assertEquals(List.of("payments","systems"),r.keywordRelevance().matchedTerms());
        assertEquals(r,engine.match(j,c));
        assertEquals(new BigDecimal("100.00"),r.overallScore());
    }
    @Test void missingRequiredCapsStrongRawScore() {
        var skills = List.of("java","python","sql","docker","git","aws","redis","react","maven","postgresql");
        var r = engine.match(job(skills,List.of(),"1"),candidate("Java Python SQL Docker Git AWS Redis React Maven",12));
        assertEquals(new BigDecimal("79.00"), r.overallScore());
        assertEquals(Recommendation.GOOD_MATCH,r.recommendation());
    }
}

