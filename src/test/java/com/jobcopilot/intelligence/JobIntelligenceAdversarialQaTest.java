package com.jobcopilot.intelligence;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static com.jobcopilot.intelligence.JobIntelligenceTestSupport.*;
import static com.jobcopilot.intelligence.JobIntelligenceResult.*;
import static org.junit.jupiter.api.Assertions.*;

/** Requirement regressions: unresolved semantic defects deliberately remain red, never disabled. */
class JobIntelligenceAdversarialQaTest {
    static JobIntelligenceResult runJson(String json, String raw) {
        return runner(new ScriptedModel(json)).run(request(new JobIntelligencePrompt.LlmFirstInput("Role", raw)));
    }
    static String skill(String quote, String importance) {
        return javaRequired().replace("Must have Java.", quote).replace("REQUIRED", importance);
    }
    static JobIntelligenceDecoder.Result.Failure decodeFailure(String json) {
        return assertInstanceOf(JobIntelligenceDecoder.Result.Failure.class, new JobIntelligenceDecoder().decode(json));
    }

    @Test void unknownKeyCannotLeakProviderContentIntoFailure() {
        String secret = "SECRET_".repeat(1000);
        var failure = decodeFailure(javaRequired().replace("\"uncertainties\":[]", "\"uncertainties\":[],\"" + secret + "\":1"));
        assertEquals(Code.UNKNOWN_FIELD, failure.code());
        assertEquals("", failure.location().path());
    }
    @Test void schemaStringLengthsUseCodePoints() {
        String emoji = new String(Character.toChars(0x1f600));
        assertInstanceOf(JobIntelligenceDecoder.Result.Success.class,
                new JobIntelligenceDecoder().decode(javaRequired().replace("\"Java\"", jsonString(emoji.repeat(300)))));
        assertEquals(Code.BOUND_VIOLATION, decodeFailure(javaRequired().replace("\"Java\"", jsonString(emoji.repeat(301)))).code());
    }
    @Test void illegalObjectNullHasExactCode() {
        assertEquals(Code.NULL_NOT_ALLOWED, decodeFailure(javaRequired().replace("\"uncertainties\":[]", "\"uncertainties\":null")).code());
    }
    @Test void metadataOmitsOversizeAndControlValues() {
        var provider = new JobIntelligenceModel.ModelAttemptResult(javaRequired(), JobIntelligenceModel.Outcome.COMPLETED,
                "SECRET_FAILURE", "x".repeat(10000), "request\nSECRET", new JobIntelligenceModel.Usage(-1L, null, -5L), Duration.ofSeconds(-1));
        var result = runner(new ScriptedModel(provider)).run(request(new JobIntelligencePrompt.LlmFirstInput("Role", "Must have Java.")));
        assertInstanceOf(Accepted.class, result);
        assertNull(result.metadata().returnedModel());
        assertNull(result.metadata().providerRequestId());
        assertNull(result.metadata().providerLatency());
        assertNull(result.metadata().tokenUsage().inputTokens());
        assertNull(result.metadata().tokenUsage().outputTokens());
        assertNull(result.metadata().tokenUsage().cachedInputTokens());
        assertFalse(result.toString().contains("SECRET"));
    }
    @Test void invalidOperationalSettingsNeverDispatch() {
        for (var settings : List.of(
                new JobIntelligencePrompt.ExecutionSettings(" ", new JobIntelligenceModel.GenerationSettings(0.0, 1), Duration.ofSeconds(1)),
                new JobIntelligencePrompt.ExecutionSettings("m", new JobIntelligenceModel.GenerationSettings(Double.NaN, 1), Duration.ofSeconds(1)),
                new JobIntelligencePrompt.ExecutionSettings("m", new JobIntelligenceModel.GenerationSettings(null, 0), Duration.ofSeconds(1)),
                new JobIntelligencePrompt.ExecutionSettings("m", new JobIntelligenceModel.GenerationSettings(null, 1), Duration.ofSeconds(Long.MAX_VALUE)))) {
            var model = new ScriptedModel(javaRequired());
            var failed = assertInstanceOf(Failed.class, runner(model).run(new JobIntelligenceRunner.Request(
                    new JobIntelligencePrompt.LlmFirstInput("R", "Must have Java."), settings)));
            assertEquals(Stage.PREFLIGHT, failed.failure().stage());
            assertFalse(failed.metadata().invocationStarted());
            assertEquals(0, model.invocations());
        }
    }
    @Test void nullProviderResultsAreContractViolations() {
        for (var model : List.of(new ScriptedModel(input -> null), new ScriptedModel(
                new JobIntelligenceModel.ModelAttemptResult(javaRequired(), null, null, null, null, null, null)))) {
            var failed = assertInstanceOf(Failed.class, runner(model).run(request(new JobIntelligencePrompt.LlmFirstInput("R", "D"))));
            assertEquals("PROVIDER_CONTRACT_VIOLATION", failed.failure().code().name());
            assertEquals(1, model.invocations());
        }
    }
    @ParameterizedTest @ValueSource(strings = {
            "Python required. Java preferred.", "Python required; Java optional.",
            "Python required, Java preferred.", "Python required and Java is a plus.",
            "Python required. Java experience.", "Python required, Java experience."
    }) void unrelatedRequiredMarkerCannotPromoteJava(String raw) {
        assertInstanceOf(Failed.class, runJson(skill(raw, "REQUIRED"), raw));
    }
    @Test void croppedEvidenceCannotRemoveNegation() {
        assertInstanceOf(Failed.class, runJson(skill("Java required", "REQUIRED"), "No Java required"));
    }
    @Test void croppedExperienceCannotRemoveExclusiveQualifier() {
        String raw = "Must have more than 3 years.";
        var result = runJson(withExperience("3 years", "3", "YEARS", "REQUIRED", "OVERALL", false, raw), raw);
        assertTrue(result instanceof Failed || ((Accepted) result).intelligence().minimumExperience().status() == JobIntelligence.MinimumStatus.AMBIGUOUS);
    }
    @Test void skillSpecificCannotBeRelabeledOverall() {
        String raw = "Must have 5 years of Java experience.";
        assertInstanceOf(Failed.class, runJson(withExperience("5 years", "5", "YEARS", "REQUIRED", "OVERALL", false, raw), raw));
    }
    @Test void conditionalCannotBeRelabeledUnconditional() {
        String raw = "Must have 3 years or a degree.";
        assertInstanceOf(Failed.class, runJson(withExperience("3 years", "3", "YEARS", "REQUIRED", "OVERALL", false, raw), raw));
    }
    @ParameterizedTest @ValueSource(strings = {"5-3 years", "-3 years", "1e3 years", "3 yearsold"})
    void invalidExperienceFormCannotBecomeKnown(String text) {
        String raw = "Must have " + text + ".";
        assertInstanceOf(Failed.class, runJson(withExperience(text, text.startsWith("5") ? "5" : "3", "YEARS", "REQUIRED", "OVERALL", false, raw), raw));
    }
    @Test void interpretationCannotBeCutFromNegatedSource() {
        String json = emptyFacts("{\"id\":\"e1\",\"source\":\"TITLE\",\"quote\":\"Senior Software Engineer\"}", "", "", "")
                .replace("\"seniority\":null", "\"seniority\":{\"value\":\"SENIOR\",\"evidenceIds\":[\"e1\"]}");
        var result = runner(new ScriptedModel(json)).run(request(new JobIntelligencePrompt.LlmFirstInput("Not a Senior Software Engineer", "")));
        assertInstanceOf(Failed.class, result);
    }
    @Test void substringInterpretationDoesNotMatchLongerWord() {
        assertFalse(InterpretationSupport.role(JobIntelligence.Role.DATA_ENGINEERING, List.of("data engineersnot")));
        assertFalse(InterpretationSupport.seniority(JobIntelligence.Seniority.SENIOR, List.of("senior2")));
    }
    @Test void technologyBoundaryHandlesSupplementaryLetters() {
        String letter = new String(Character.toChars(0x10400));
        assertFalse(TechnologyTokens.contains(letter + "Java", "Java"));
        assertFalse(TechnologyTokens.contains("Java" + letter, "Java"));
    }
    @Test void decimalsAreExactAndAcceptedCollectionsImmutable() {
        String raw = "Must have 1.25 years.";
        var accepted = assertInstanceOf(Accepted.class, runJson(withExperience("1.25 years", "1.250", "YEARS", "REQUIRED", "OVERALL", false, raw), raw));
        assertEquals(0, new BigDecimal("15").compareTo(accepted.intelligence().minimumExperience().months()));
        assertThrows(UnsupportedOperationException.class, () -> accepted.intelligence().evidence().clear());
        assertThrows(UnsupportedOperationException.class, () -> accepted.intelligence().facts().experienceClauses().getFirst().evidenceIds().clear());
    }
}
