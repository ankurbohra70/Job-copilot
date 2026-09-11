package com.jobcopilot.intelligence;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import static com.jobcopilot.intelligence.JobIntelligence.Importance;
import static com.jobcopilot.intelligence.JobIntelligence.MinimumStatus;
import static com.jobcopilot.intelligence.JobIntelligence.Scope;
import static com.jobcopilot.intelligence.JobIntelligence.Unit;
import static com.jobcopilot.intelligence.JobIntelligenceResult.Code;
import static org.junit.jupiter.api.Assertions.*;

class JobIntelligenceValidatorTest {
    private final JobIntelligenceDecoder decoder = new JobIntelligenceDecoder();
    private final JobIntelligenceValidator validator = new JobIntelligenceValidator();

    @Test void validLlmFirstSkillIsAccepted() {
        var accepted = accept(JobIntelligenceTestSupport.javaRequired(), "Engineer", "Must have Java.");
        assertEquals("Java", accepted.facts().skills().getFirst().name());
        assertEquals(MinimumStatus.NOT_STATED, accepted.minimumExperience().status());
        assertNull(accepted.minimumExperience().months());
    }

    @Test void fabricatedQuoteIsRejected() {
        assertEquals(Code.EVIDENCE_QUOTE_MISMATCH, fail(JobIntelligenceTestSupport.javaRequired(), "Engineer", "Must have Python.").code());
    }

    @Test void quoteInWrongSourceIsRejected() {
        String json = JobIntelligenceTestSupport.javaRequired().replace("DESCRIPTION", "TITLE");
        assertEquals(Code.EVIDENCE_QUOTE_MISMATCH, fail(json, "Engineer", "Must have Java.").code());
        var accepted = accept(json, "Must have Java.", "Unrelated description.");
        assertEquals("Java", accepted.facts().skills().getFirst().name());
    }

    @Test void duplicateEvidenceIdIsRejected() {
        String json = """
                {"facts":{"skills":[{"name":"Java","importance":"REQUIRED","evidenceIds":["e1"]}],\
                "experienceClauses":[],"qualifications":[]},"interpretations":{"roleFamily":null,"seniority":null,\
                "responsibilities":[],"technicalConcepts":[]},"evidence":[\
                {"id":"e1","source":"DESCRIPTION","quote":"Must have Java."},\
                {"id":"e1","source":"DESCRIPTION","quote":"Must have Java."}],"uncertainties":[]}
                """;
        assertEquals(Code.DUPLICATE_EVIDENCE_ID, fail(json, "Role", "Must have Java.").code());
    }

    @Test void danglingEvidenceIdIsRejected() {
        String json = JobIntelligenceTestSupport.javaRequired().replace("e1", "missing");
        json = json.replace("\"id\":\"missing\"", "\"id\":\"e1\"");
        assertEquals(Code.DANGLING_EVIDENCE_ID, fail(json, "Role", "Must have Java.").code());
    }

    @Test void canonicalOnlyHybridClaimFails() {
        assertEquals(Code.EVIDENCE_QUOTE_MISMATCH, fail(JobIntelligenceTestSupport.javaRequired(), "Role", "See requisition.").code());
    }

    @Test void javaDoesNotMatchJavascript() {
        String json = JobIntelligenceTestSupport.javaRequired().replace("Must have Java.", "Must have JavaScript.");
        assertEquals(Code.FACT_NOT_GROUNDED, fail(json, "Role", "Must have JavaScript.").code());
    }

    @Test void cDoesNotMatchCpp() {
        String json = """
                {"facts":{"skills":[{"name":"C","importance":"REQUIRED","evidenceIds":["e1"]}],\
                "experienceClauses":[],"qualifications":[]},"interpretations":{"roleFamily":null,"seniority":null,\
                "responsibilities":[],"technicalConcepts":[]},"evidence":[{"id":"e1","source":"DESCRIPTION",\
                "quote":"Must have C++."}],"uncertainties":[]}
                """;
        assertEquals(Code.FACT_NOT_GROUNDED, fail(json, "Role", "Must have C++.").code());
    }

    @Test void negatedAndOptionalDoNotSupportRequired() {
        String optional = JobIntelligenceTestSupport.javaRequired().replace("Must have Java.", "Java is optional.");
        assertEquals(Code.UNSUPPORTED_CLAIM_FORM, fail(optional, "Role", "Java is optional.").code());
        String negated = JobIntelligenceTestSupport.javaRequired().replace("Must have Java.", "No Java required.");
        assertEquals(Code.UNSUPPORTED_CLAIM_FORM, fail(negated, "Role", "No Java required.").code());
    }

    @Test void abstentionIsValid() throws Exception {
        var accepted = accept(JobIntelligenceTestSupport.fixture("abstention"), "Role", "");
        assertTrue(accepted.facts().skills().isEmpty());
        assertNull(accepted.interpretations().roleFamily());
        assertEquals(MinimumStatus.NOT_STATED, accepted.minimumExperience().status());
    }

    @Test void unsupportedInterpretationIsRejected() {
        String json = """
                {"facts":{"skills":[],"experienceClauses":[],"qualifications":[]},\
                "interpretations":{"roleFamily":{"value":"BACKEND","evidenceIds":["e1"]},"seniority":null,\
                "responsibilities":[],"technicalConcepts":[]},\
                "evidence":[{"id":"e1","source":"DESCRIPTION","quote":"Must have Java."}],"uncertainties":[]}
                """;
        assertEquals(Code.UNSUPPORTED_INTERPRETATION, fail(json, "Role", "Must have Java.").code());
    }

    @Test void supportedBackendInterpretationIsAccepted() {
        String json = """
                {"facts":{"skills":[],"experienceClauses":[],"qualifications":[]},\
                "interpretations":{"roleFamily":{"value":"BACKEND","evidenceIds":["e1"]},"seniority":null,\
                "responsibilities":[],"technicalConcepts":[]},\
                "evidence":[{"id":"e1","source":"TITLE","quote":"Backend engineer"}],"uncertainties":[]}
                """;
        assertEquals(JobIntelligence.Role.BACKEND, accept(json, "Backend engineer", "Build services.").interpretations().roleFamily().value());
    }

    @Test void uncertaintyDoesNotValidateFabricatedClaim() {
        String json = """
                {"facts":{"skills":[{"name":"Java","importance":"REQUIRED","evidenceIds":["e1"]}],\
                "experienceClauses":[],"qualifications":[]},"interpretations":{"roleFamily":null,"seniority":null,\
                "responsibilities":[],"technicalConcepts":[]},"evidence":[{"id":"e1","source":"DESCRIPTION","quote":"Must have Java."}],\
                "uncertainties":[{"code":"INSUFFICIENT_CONTEXT","target":"facts.skills","evidenceIds":[]}]}
                """;
        assertEquals(Code.EVIDENCE_QUOTE_MISMATCH, fail(json, "Role", "Unrelated.").code());
    }

    @Test void experienceFormsDeriveExpectedMinima() {
        assertKnown("3+ years", "3", "YEARS", "Must have 3+ years.", new BigDecimal("36"));
        assertKnown("at least 3 years", "3", "YEARS", "Must have at least 3 years.", new BigDecimal("36"));
        assertKnown("minimum 3 years", "3", "YEARS", "minimum 3 years required", new BigDecimal("36"));
        assertKnown("3-5 years", "3", "YEARS", "Must have 3-5 years.", new BigDecimal("36"));
        assertKnown("2.5 years", "2.5", "YEARS", "Must have 2.5 years.", new BigDecimal("30.0"));
        assertKnown("18 months", "18", "MONTHS", "Must have 18 months.", new BigDecimal("18"));
    }

    @Test void moreThanYearsIsAmbiguous() {
        String json = JobIntelligenceTestSupport.withExperience("more than 3 years", "3", "YEARS", "REQUIRED", "OVERALL", false, "Must have more than 3 years.");
        assertEquals(MinimumStatus.AMBIGUOUS, accept(json, "Role", "Must have more than 3 years.").minimumExperience().status());
    }

    @Test void preferredAndSkillSpecificDoNotSetGlobalMinimum() {
        String preferred = JobIntelligenceTestSupport.withExperience("5 years", "5", "YEARS", "PREFERRED", "OVERALL", false, "preferred 5 years");
        assertEquals(MinimumStatus.NOT_STATED, accept(preferred, "Role", "preferred 5 years").minimumExperience().status());
        String skill = JobIntelligenceTestSupport.withExperience("5 years", "5", "YEARS", "REQUIRED", "SKILL_SPECIFIC", false, "Must have 5 years of Java");
        assertEquals(MinimumStatus.NOT_STATED, accept(skill, "Role", "Must have 5 years of Java").minimumExperience().status());
    }

    @Test void multipleCompatibleOverallTakeMaximum() {
        String json = """
                {"facts":{"skills":[],"experienceClauses":[\
                {"text":"3+ years","minimum":3,"unit":"YEARS","importance":"REQUIRED","scope":"OVERALL","conditional":false,"evidenceIds":["e1"]},\
                {"text":"5+ years","minimum":5,"unit":"YEARS","importance":"REQUIRED","scope":"OVERALL","conditional":false,"evidenceIds":["e2"]}],\
                "qualifications":[]},"interpretations":{"roleFamily":null,"seniority":null,"responsibilities":[],"technicalConcepts":[]},\
                "evidence":[{"id":"e1","source":"DESCRIPTION","quote":"Must have 3+ years."},\
                {"id":"e2","source":"DESCRIPTION","quote":"Must have 5+ years."}],"uncertainties":[]}
                """;
        assertEquals(new BigDecimal("60"), accept(json, "Role", "Must have 3+ years. Must have 5+ years.").minimumExperience().months());
    }

    @Test void contradictoryRangeAndHigherMinimumAreAmbiguous() {
        String json = """
                {"facts":{"skills":[],"experienceClauses":[\
                {"text":"3-5 years","minimum":3,"unit":"YEARS","importance":"REQUIRED","scope":"OVERALL","conditional":false,"evidenceIds":["e1"]},\
                {"text":"8+ years","minimum":8,"unit":"YEARS","importance":"REQUIRED","scope":"OVERALL","conditional":false,"evidenceIds":["e2"]}],\
                "qualifications":[]},"interpretations":{"roleFamily":null,"seniority":null,"responsibilities":[],"technicalConcepts":[]},\
                "evidence":[{"id":"e1","source":"DESCRIPTION","quote":"Must have 3-5 years."},\
                {"id":"e2","source":"DESCRIPTION","quote":"Must have 8+ years."}],"uncertainties":[]}
                """;
        assertEquals(MinimumStatus.AMBIGUOUS, accept(json, "Role", "Must have 3-5 years. Must have 8+ years.").minimumExperience().status());
    }

    @Test void conditionalAlternativesAreAmbiguous() {
        String json = JobIntelligenceTestSupport.withExperience("3 years", "3", "YEARS", "REQUIRED", "OVERALL", true, "Must have 3 years or a degree");
        assertEquals(MinimumStatus.AMBIGUOUS, accept(json, "Role", "Must have 3 years or a degree").minimumExperience().status());
    }

    private void assertKnown(String text, String minimum, String unit, String source, BigDecimal months) {
        String json = JobIntelligenceTestSupport.withExperience(text, minimum, unit, "REQUIRED", "OVERALL", false, source);
        var accepted = accept(json, "Role", source);
        assertEquals(MinimumStatus.KNOWN, accepted.minimumExperience().status());
        assertEquals(0, months.compareTo(accepted.minimumExperience().months()), accepted.minimumExperience().months().toPlainString());
    }

    private JobIntelligence accept(String json, String title, String description) {
        var decoded = decoder.decode(json);
        assertInstanceOf(JobIntelligenceDecoder.Result.Success.class, decoded, String.valueOf(decoded));
        var validated = validator.validate(((JobIntelligenceDecoder.Result.Success) decoded).output(), title, description);
        assertInstanceOf(JobIntelligenceValidator.Result.Success.class, validated, String.valueOf(validated));
        return ((JobIntelligenceValidator.Result.Success) validated).intelligence();
    }

    private JobIntelligenceValidator.Result.Failure fail(String json, String title, String description) {
        var decoded = decoder.decode(json);
        assertInstanceOf(JobIntelligenceDecoder.Result.Success.class, decoded, String.valueOf(decoded));
        var validated = validator.validate(((JobIntelligenceDecoder.Result.Success) decoded).output(), title, description);
        assertInstanceOf(JobIntelligenceValidator.Result.Failure.class, validated, String.valueOf(validated));
        return (JobIntelligenceValidator.Result.Failure) validated;
    }
}
