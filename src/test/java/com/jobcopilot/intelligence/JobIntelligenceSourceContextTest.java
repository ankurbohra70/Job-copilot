package com.jobcopilot.intelligence;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static com.jobcopilot.intelligence.JobIntelligenceTestSupport.*;
import static com.jobcopilot.intelligence.JobIntelligenceResult.*;
import static com.jobcopilot.intelligence.JobIntelligence.*;
import static org.junit.jupiter.api.Assertions.*;

class JobIntelligenceSourceContextTest {
    private final JsonMapper json = JsonMapper.builder().build();

    private JobIntelligenceResult skill(String raw, String quote, String importance) {
        var node = (ObjectNode) json.readTree(javaRequired());
        ((ObjectNode) node.get("facts").get("skills").get(0)).put("importance", importance);
        ((ObjectNode) node.get("evidence").get(0)).put("quote", quote);
        return run(node.toString(), raw);
    }
    private JobIntelligenceResult run(String candidate, String raw) {
        return runner(new ScriptedModel(candidate)).run(request(new JobIntelligencePrompt.LlmFirstInput("Role", raw)));
    }
    private JobIntelligenceResult experience(String raw, String text, String minimum, String unit, String scope, boolean conditional) {
        return run(withExperience(text, minimum, unit, "REQUIRED", scope, conditional, text), raw);
    }
    private void known(JobIntelligenceResult result, String months) {
        var accepted = assertInstanceOf(Accepted.class, result);
        assertEquals(MinimumStatus.KNOWN, accepted.intelligence().minimumExperience().status());
        assertEquals(0, new BigDecimal(months).compareTo(accepted.intelligence().minimumExperience().months()));
    }
    @ParameterizedTest @ValueSource(strings = {
            "No Java required", "Java is not required", "Java preferred", "Java is optional", "Java or Python required",
            "Must have Java, though Java is optional", "Must have Java if assigned to that team", "No\nJava required"
    }) void croppingCannotTurnModifiersIntoRequired(String raw) {
        assertInstanceOf(Failed.class, skill(raw, "Java", "REQUIRED"));
    }
    @Test void croppedPreferredSkillCanRemainPreferred() {
        assertInstanceOf(Accepted.class, skill("Java preferred", "Java", "PREFERRED"));
    }
    @Test void qualificationsAndInterpretationsAlsoUseCompleteContext() {
        var node = (ObjectNode) json.readTree(emptyFacts("", "", "", ""));
        ((tools.jackson.databind.node.ArrayNode) node.get("facts").get("qualifications"))
                .add(json.readTree("{\"text\":\"degree\",\"importance\":\"REQUIRED\",\"evidenceIds\":[\"e1\"]}"));
        ((tools.jackson.databind.node.ArrayNode) node.get("evidence"))
                .add(json.readTree("{\"id\":\"e1\",\"source\":\"DESCRIPTION\",\"quote\":\"degree\"}"));
        assertInstanceOf(Failed.class, run(node.toString(), "Java required and degree preferred"));
        var senior = (ObjectNode) json.readTree(emptyFacts("{\"id\":\"e1\",\"source\":\"DESCRIPTION\",\"quote\":\"Senior\"}", "", "", ""));
        ((ObjectNode) senior.get("interpretations")).set("seniority", json.readTree("{\"value\":\"SENIOR\",\"evidenceIds\":[\"e1\"]}"));
        assertInstanceOf(Failed.class, run(senior.toString(), "Senior or Junior Software Engineer"));
        assertInstanceOf(Failed.class, run(senior.toString(), "Senior Software Engineer. Not a Senior Software Engineer."));
    }
    @Test void distantModifierInCompleteClauseCannotBeLostByCharacterWindow() {
        assertInstanceOf(Failed.class, skill("Must have Java " + "experience ".repeat(15) + "preferred", "Java", "REQUIRED"));
    }
    @ParameterizedTest @ValueSource(strings = {
            "Java required. No Java required.", "No Java required. Java required.",
            "Java required. Java preferred.", "Java required. Java optional."
    }) void allDuplicateOccurrencesMustSupportClaim(String raw) {
        assertInstanceOf(Failed.class, skill(raw, "Java", "REQUIRED"));
    }
    @Test void allPositiveDuplicateOccurrencesPass() {
        assertInstanceOf(Accepted.class, skill("Java required. Must have Java.", "Java", "REQUIRED"));
    }
    @Test void sameQuoteWithMixedQuantitySemanticsFails() {
        assertInstanceOf(Failed.class, experience("Must have 3 years. Must have more than 3 years.", "3 years", "3", "YEARS", "OVERALL", false));
    }
    @ParameterizedTest @ValueSource(strings = {
            "3+ years of software engineering experience", "at least 4 years of professional development experience"
    }) void generalScopeIsEstablished(String raw) {
        String number = raw.startsWith("3") ? "3" : "4";
        known(experience(raw, number + (number.equals("3") ? "+ years" : " years"), number, "YEARS", "OVERALL", false), number.equals("3") ? "36" : "48");
        assertInstanceOf(Failed.class, experience(raw, number + (number.equals("3") ? "+ years" : " years"), number, "YEARS", "SKILL_SPECIFIC", false));
    }
    @ParameterizedTest @ValueSource(strings = {"of Java experience", "with Spring Boot", "using Kubernetes", "of Java"})
    void namedSkillScopeMustMatch(String suffix) {
        String raw = "Must have 5 years " + suffix;
        var accepted = assertInstanceOf(Accepted.class, experience(raw, "5 years", "5", "YEARS", "SKILL_SPECIFIC", false));
        assertEquals(MinimumStatus.NOT_STATED, accepted.intelligence().minimumExperience().status());
        assertInstanceOf(Failed.class, experience(raw, "5 years", "5", "YEARS", "OVERALL", false));
    }
    @Test void abbreviatedUnitsDoNotHideSkillBinding() {
        String raw = "Must have 5 yrs. of Java experience";
        assertInstanceOf(Failed.class, experience(raw, "5 yrs.", "5", "YEARS", "OVERALL", false));
        assertInstanceOf(Accepted.class, experience(raw, "5 yrs.", "5", "YEARS", "SKILL_SPECIFIC", false));
    }
    @ParameterizedTest @ValueSource(strings = {"of some kind of experience", "in a related capacity", "of domain-adjacent exposure"})
    void ambiguousScopeFailsClosed(String suffix) {
        assertInstanceOf(Failed.class, experience("Must have 3 years " + suffix, "3 years", "3", "YEARS", "OVERALL", false));
    }
    @Test void relevantScopeIsNotOverall() {
        String raw = "Must have 3 years of relevant experience";
        assertInstanceOf(Accepted.class, experience(raw, "3 years", "3", "YEARS", "RELEVANT", false));
        assertInstanceOf(Failed.class, experience(raw, "3 years", "3", "YEARS", "OVERALL", false));
    }
    @ParameterizedTest @ValueSource(strings = {
            "Must have 3 years or degree", "Must have either 3 years or equivalent certification",
            "Must have 3 years of experience or a bachelor's degree", "Must have 3 years if assigned to backend",
            "Must have 3 years unless holding a certification", "Must have 3 years in lieu of a degree",
            "Must have 3 years or equivalent experience", "If assigned to backend, must have 3 years"
    }) void localConditionalityMustMatch(String raw) {
        assertInstanceOf(Failed.class, experience(raw, "3 years", "3", "YEARS", "OVERALL", false));
        var accepted = assertInstanceOf(Accepted.class, experience(raw, "3 years", "3", "YEARS", "OVERALL", true));
        assertEquals(MinimumStatus.AMBIGUOUS, accepted.intelligence().minimumExperience().status());
        assertNull(accepted.intelligence().minimumExperience().months());
    }
    @Test void unrelatedAlternativeDoesNotChangeConditionality() {
        String raw = "Work remotely or onsite. Must have 3 years of experience.";
        known(experience(raw, "3 years", "3", "YEARS", "OVERALL", false), "36");
        assertInstanceOf(Failed.class, experience(raw, "3 years", "3", "YEARS", "OVERALL", true));
    }
    @Test void unsupportedLocalAlternativeFailsClosed() {
        assertInstanceOf(Failed.class, experience("Must have 3 years of Java or Python", "3 years", "3", "YEARS", "SKILL_SPECIFIC", true));
        assertInstanceOf(Failed.class, experience("Must have 3 years or 5 years", "3 years", "3", "YEARS", "OVERALL", true));
    }
    @Test void croppedSourceFormsDriveDerivationWithoutChangingStoredText() {
        known(experience("Must have at least 3 years", "3 years", "3", "YEARS", "OVERALL", false), "36");
        known(experience("Must have minimum 3 years", "3 years", "3", "YEARS", "OVERALL", false), "36");
        // Upper-end substring is provenance; source range still establishes the lower bound.
        known(experience("Must have 3-5 years", "5 years", "3", "YEARS", "OVERALL", false), "36");
        assertInstanceOf(Failed.class, experience("Must have 3-5 years", "5 years", "5", "YEARS", "OVERALL", false));
        var exclusive = assertInstanceOf(Accepted.class, experience("Must have more than 3 years of experience", "3 years", "3", "YEARS", "OVERALL", false));
        assertEquals(MinimumStatus.AMBIGUOUS, exclusive.intelligence().minimumExperience().status());
        assertEquals("3 years", exclusive.intelligence().facts().experienceClauses().getFirst().text());
        assertEquals("3 years", exclusive.intelligence().evidence().getFirst().quote());
        known(experience("Must have 1.25 years of experience", "1.25 years", "1.25", "YEARS", "OVERALL", false), "15");
        known(experience("Must have 1.25 months of experience", "1.25 months", "1.25", "MONTHS", "OVERALL", false), "1.25");
    }
    @Test void contextBoundsRejectInsteadOfCropping() {
        assertEquals(1, SourceContext.all("x".repeat(1023) + "J", "J").size());
        assertTrue(SourceContext.all("x".repeat(1024) + "J", "J").isEmpty());
        String emoji = new String(Character.toChars(0x1f600));
        assertEquals(1, SourceContext.all(emoji.repeat(1023) + "J", "J").size());
        assertEquals(32, SourceContext.all("J. ".repeat(32), "J").size());
        assertTrue(SourceContext.all("J. ".repeat(33), "J").isEmpty());
        assertTrue(SourceContext.all("x".repeat(1_000_001), "x").isEmpty());
        assertInstanceOf(Failed.class, skill("x".repeat(1_000_001), "Java", "REQUIRED"));
        assertTrue(SourceContext.all("First. Second.", "First. Second.").isEmpty());
    }
    @Test void titleContextNeverComesFromDescriptionOrCanonical() {
        var node = (ObjectNode) json.readTree(javaRequired());
        ((ObjectNode) node.get("evidence").get(0)).put("source", "TITLE").put("quote", "Java");
        var result = runner(new ScriptedModel(node.toString())).run(request(new JobIntelligencePrompt.HybridInput(
                "No Java required", "Must have Java", new JobIntelligencePrompt.CanonicalRequirements(List.of("Java"), List.of(), null))));
        assertInstanceOf(Failed.class, result);
    }
}
