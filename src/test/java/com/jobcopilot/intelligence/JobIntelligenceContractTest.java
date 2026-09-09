package com.jobcopilot.intelligence;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class JobIntelligenceContractTest {
    private final JsonMapper json = JsonMapper.builder().build();

    @Test void sharedSchemaAndWireRecordsAgreeOnEveryObjectField() throws Exception {
        var schema = resource("/job-intelligence/schemas/v1/model-output.schema.json");
        assertRecordShape(JobIntelligenceModel.Output.class, schema);
        for (Class<?> type : List.of(JobIntelligence.Facts.class, JobIntelligence.Interpretations.class,
                JobIntelligence.Skill.class, JobIntelligence.ExperienceClause.class, JobIntelligence.Qualification.class,
                JobIntelligence.TextClaim.class, JobIntelligence.RoleClaim.class, JobIntelligence.SeniorityClaim.class,
                JobIntelligence.Evidence.class, JobIntelligence.Uncertainty.class)) {
            assertRecordShape(type, schema.path("$defs").path(type.getSimpleName()));
        }
        assertFalse(schema.path("properties").has("minimumExperience"));
        assertFalse(Arrays.stream(JobIntelligenceModel.Output.class.getRecordComponents()).anyMatch(c -> c.getName().equals("minimumExperience")));
        assertTrue(Arrays.stream(JobIntelligence.class.getRecordComponents()).anyMatch(c -> c.getName().equals("minimumExperience")));
    }

    @Test void schemaFreezesEvidenceSourcesEnumsAndCollectionBounds() throws Exception {
        var defs = resource("/job-intelligence/schemas/v1/model-output.schema.json").path("$defs");
        assertEnum(JobIntelligence.Source.class, defs.path("Evidence").path("properties").path("source"));
        assertEnum(JobIntelligence.Importance.class, defs.path("Skill").path("properties").path("importance"));
        assertEnum(JobIntelligence.Scope.class, defs.path("ExperienceClause").path("properties").path("scope"));
        assertEnum(JobIntelligence.Role.class, defs.path("RoleClaim").path("properties").path("value"));
        assertEnum(JobIntelligence.Seniority.class, defs.path("SeniorityClaim").path("properties").path("value"));
        assertEnum(JobIntelligence.UncertaintyCode.class, defs.path("Uncertainty").path("properties").path("code"));
        assertEquals(Set.of("TITLE", "DESCRIPTION"), Arrays.stream(JobIntelligence.Source.values()).map(Enum::name).collect(Collectors.toSet()));
        assertEquals(40, defs.path("Facts").path("properties").path("skills").path("maxItems").asInt());
        assertEquals(8, defs.path("Facts").path("properties").path("experienceClauses").path("maxItems").asInt());
        assertEquals(500, defs.path("Evidence").path("properties").path("quote").path("maxLength").asInt());
        assertEquals(3, defs.path("Skill").path("properties").path("evidenceIds").path("maxItems").asInt());
    }

    @Test void strategyFixturesDecodeToSameCandidateContractAndSupportAbstention() throws Exception {
        var hybrid = fixture("valid-hybrid");
        var first = fixture("valid-llm-first");
        assertEquals(hybrid, first);
        assertEquals("Java", hybrid.facts().skills().getFirst().name());
        var abstention = fixture("abstention");
        assertNull(abstention.interpretations().roleFamily());
        assertTrue(abstention.facts().skills().isEmpty());
        assertFalse(abstention.uncertainties().isEmpty());
        var invalid = resource("/job-intelligence/fixtures/invalid-canonical-evidence.json");
        assertThrows(RuntimeException.class, () -> json.treeToValue(invalid, JobIntelligenceModel.Output.class));
    }

    @Test void collectionContractsDoNotRetainMutableCallerState() {
        var ids = new ArrayList<>(List.of("e1"));
        var skill = new JobIntelligence.Skill("Java", JobIntelligence.Importance.REQUIRED, ids);
        ids.clear();
        assertEquals(List.of("e1"), skill.evidenceIds());
        assertThrows(UnsupportedOperationException.class, () -> skill.evidenceIds().clear());
        var skills = new ArrayList<>(List.of(skill));
        var facts = new JobIntelligence.Facts(skills, List.of(), List.of());
        skills.clear();
        assertEquals(List.of(skill), facts.skills());
        var canonical = new ArrayList<>(List.of("manual"));
        var context = new JobIntelligencePrompt.CanonicalRequirements(canonical, List.of(), null);
        canonical.clear();
        assertEquals(List.of("manual"), context.requiredSkills());
    }

    private JobIntelligenceModel.Output fixture(String name) throws Exception {
        return json.treeToValue(resource("/job-intelligence/fixtures/" + name + ".json"), JobIntelligenceModel.Output.class);
    }
    private JsonNode resource(String name) throws Exception {
        try (var stream = getClass().getResourceAsStream(name)) {
            assertNotNull(stream, name);
            return json.readTree(stream);
        }
    }
    private void assertRecordShape(Class<?> type, JsonNode schema) {
        var names = Arrays.stream(type.getRecordComponents()).map(c -> c.getName()).collect(Collectors.toSet());
        assertEquals(names, schema.path("properties").propertyNames(), type.getName());
        Set<String> required = new java.util.HashSet<>();
        schema.path("required").forEach(n -> required.add(n.asString()));
        assertEquals(names, required);
        assertFalse(schema.path("additionalProperties").asBoolean(true));
    }
    private <T extends Enum<T>> void assertEnum(Class<T> type, JsonNode schema) {
        Set<String> values = new java.util.HashSet<>();
        schema.path("enum").forEach(n -> values.add(n.asString()));
        assertEquals(Arrays.stream(type.getEnumConstants()).map(Enum::name).collect(Collectors.toSet()), values);
    }
}
