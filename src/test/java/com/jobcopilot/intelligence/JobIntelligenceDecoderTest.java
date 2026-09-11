package com.jobcopilot.intelligence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static com.jobcopilot.intelligence.JobIntelligenceResult.Code;
import static com.jobcopilot.intelligence.JobIntelligenceTestSupport.javaRequired;
import static org.junit.jupiter.api.Assertions.*;

class JobIntelligenceDecoderTest {
    private final JobIntelligenceDecoder decoder = new JobIntelligenceDecoder();

    @Test void validObjectDecodes() {
        var result = decoder.decode(javaRequired());
        assertInstanceOf(JobIntelligenceDecoder.Result.Success.class, result);
        var output = ((JobIntelligenceDecoder.Result.Success) result).output();
        assertEquals("Java", output.facts().skills().getFirst().name());
    }

    @Test void truncatedJsonFails() {
        assertCode("{", Code.MALFORMED_JSON);
        assertCode("{\"facts\":", Code.MALFORMED_JSON);
    }

    @Test void trailingProseAndSecondObjectFail() {
        assertCode(javaRequired() + " trailing", Code.TRAILING_CONTENT);
        assertCode(javaRequired() + "{\"facts\":{}}", Code.TRAILING_CONTENT);
    }

    @Test void markdownFenceFails() {
        assertCode("```json\n" + javaRequired() + "\n```", Code.NOT_SINGLE_OBJECT);
    }

    @Test void duplicateKeysFailAtEveryDepth() {
        assertCode("{\"facts\":{\"skills\":[],\"experienceClauses\":[],\"qualifications\":[]},\"interpretations\":{\"roleFamily\":null,\"seniority\":null,\"responsibilities\":[],\"technicalConcepts\":[]},\"evidence\":[],\"uncertainties\":[],\"facts\":{\"skills\":[],\"experienceClauses\":[],\"qualifications\":[]}}",
                Code.DUPLICATE_FIELD);
        assertCode("{\"facts\":{\"skills\":[],\"skills\":[],\"experienceClauses\":[],\"qualifications\":[]},\"interpretations\":{\"roleFamily\":null,\"seniority\":null,\"responsibilities\":[],\"technicalConcepts\":[]},\"evidence\":[],\"uncertainties\":[]}",
                Code.DUPLICATE_FIELD);
    }

    @Test void unknownFieldAndProviderMinimumExperienceFail() {
        assertEquals(Code.UNKNOWN_FIELD, failure(javaRequired().replace("\"uncertainties\":[]", "\"uncertainties\":[],\"extra\":1")).code());
        assertEquals(Code.UNKNOWN_FIELD, failure(javaRequired().replace("\"uncertainties\":[]", "\"uncertainties\":[],\"minimumExperience\":{\"status\":\"KNOWN\",\"months\":12}")).code());
    }

    @Test void invalidEnumFails() {
        assertEquals(Code.INVALID_ENUM, failure(javaRequired().replace("REQUIRED", "NICE_TO_HAVE")).code());
        assertEquals(Code.INVALID_ENUM, failure(javaRequired().replace("DESCRIPTION", "CANONICAL_REQUIREMENTS")).code());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"facts\":null,\"interpretations\":{\"roleFamily\":null,\"seniority\":null,\"responsibilities\":[],\"technicalConcepts\":[]},\"evidence\":[],\"uncertainties\":[]}",
            "{\"interpretations\":{\"roleFamily\":null,\"seniority\":null,\"responsibilities\":[],\"technicalConcepts\":[]},\"evidence\":[],\"uncertainties\":[]}"
    })
    void nullAndMissingRequiredFieldsFail(String json) {
        Code code = failure(json).code();
        assertTrue(code == Code.NULL_NOT_ALLOWED || code == Code.MISSING_FIELD || code == Code.TYPE_MISMATCH, code.name());
    }

    @Test void typeBoundAndNullElementViolationsFail() {
        assertEquals(Code.TYPE_MISMATCH, failure(javaRequired().replace("\"Must have Java.\"", "1")).code());
        assertEquals(Code.TYPE_MISMATCH, failure(javaRequired().replace("[\"e1\"]", "\"e1\"")).code());
        assertEquals(Code.TYPE_MISMATCH, failure(javaRequired().replace("\"Java\"", "true")).code());
        assertEquals(Code.NULL_COLLECTION_ELEMENT, failure(javaRequired().replace("[\"e1\"]", "[null]")).code());
        assertEquals(Code.BOUND_VIOLATION, failure(javaRequired().replace("\"Java\"", "\"\"")).code());
        String longQuote = "x".repeat(501);
        assertEquals(Code.BOUND_VIOLATION, failure(javaRequired().replace("Must have Java.", longQuote)).code());
    }

    @Test void numericStringAndCommentsAreRejected() {
        String clause = JobIntelligenceTestSupport.withExperience("3 years", "\"3\"", "YEARS", "REQUIRED", "OVERALL", false, "3 years");
        assertEquals(Code.TYPE_MISMATCH, failure(clause).code());
        assertEquals(Code.MALFORMED_JSON, failure(javaRequired().replace("{", "{ /* comment */")).code());
    }

    @Test void decoderDoesNotLeakRejectedValues() {
        var failed = failure("{\"minimumExperience\":1}");
        assertFalse(failed.location().path().contains("1"));
        assertNotEquals("{\"minimumExperience\":1}", failed.location().path());
    }

    private JobIntelligenceDecoder.Result.Failure failure(String json) {
        var result = decoder.decode(json);
        assertInstanceOf(JobIntelligenceDecoder.Result.Failure.class, result, String.valueOf(result));
        return (JobIntelligenceDecoder.Result.Failure) result;
    }

    private void assertCode(String json, Code code) {
        assertEquals(code, failure(json).code());
    }
}
