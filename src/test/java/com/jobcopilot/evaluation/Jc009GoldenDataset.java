package com.jobcopilot.evaluation;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class Jc009GoldenDataset {
    public static final String VERSION = "jc009-offline-evaluation-v1";
    private static final List<String> STAGES = List.of(
            "candidateExtraction", "jobRequirementExtraction", "matchingRanking", "decisionReadiness");
    private final JsonNode root;

    private Jc009GoldenDataset(JsonNode root) {
        this.root = root;
    }

    public static Jc009GoldenDataset load() {
        try (InputStream input = Jc009GoldenDataset.class.getResourceAsStream(
                "/jc009/evaluation/v1/golden-cases.json")) {
            if (input == null) throw invalid("missing golden evaluation resource");
            JsonNode root = JsonMapper.builder().build().readTree(input);
            validate(root);
            return new Jc009GoldenDataset(root);
        } catch (IOException failure) {
            throw new IllegalArgumentException("cannot read golden evaluation resource", failure);
        }
    }

    static Jc009GoldenDataset parse(String json) {
        JsonNode root = JsonMapper.builder().build().readTree(json);
        validate(root);
        return new Jc009GoldenDataset(root);
    }

    public String text(String name) {
        return requiredText(root, name);
    }

    public List<JsonNode> cases(String stage) {
        if (!STAGES.contains(stage)) throw invalid("unknown evaluation stage: " + stage);
        List<JsonNode> result = new ArrayList<>();
        root.path(stage).path("cases").forEach(result::add);
        return List.copyOf(result);
    }

    public JsonNode section(String stage) {
        if (!STAGES.contains(stage)) throw invalid("unknown evaluation stage: " + stage);
        return root.path(stage);
    }

    private static void validate(JsonNode root) {
        require(root != null && root.isObject(), "dataset root must be an object");
        require(VERSION.equals(requiredText(root, "datasetVersion")), "unexpected dataset version");
        require("HUMAN_REVIEWED".equals(requiredText(root, "labelAuthority")),
                "labels must be human reviewed");
        require(!requiredText(root, "reviewedBy").isBlank(), "reviewer role is required");
        require(requiredText(root, "reviewedAt").matches("\\d{4}-\\d{2}-\\d{2}"),
                "review date must be ISO-8601");
        require("v1".equals(requiredText(root, "matchingVocabularyVersion")),
                "matching vocabulary version mismatch");
        require("jc005-v1".equals(requiredText(root, "jobRequirementExtractorVersion")),
                "job extractor version mismatch");
        require("rules-v1".equals(requiredText(root, "candidateExtractorVersion")),
                "candidate extractor version mismatch");
        require("deterministic-v1".equals(requiredText(root, "matchingAlgorithmVersion")),
                "matching algorithm version mismatch");
        require("application-decision-v1".equals(requiredText(root, "decisionPolicyVersion")),
                "decision policy version mismatch");

        Set<String> globalIds = new HashSet<>();
        for (String stage : STAGES) {
            JsonNode section = root.path(stage);
            require(section.isObject(), "missing stage: " + stage);
            require(VERSION.equals(requiredText(section, "schemaVersion")),
                    "stage schema version mismatch: " + stage);
            JsonNode cases = section.path("cases");
            require(cases.isArray() && !cases.isEmpty(), "stage cases are required: " + stage);
            for (JsonNode value : cases) {
                String id = requiredText(value, "id");
                require(id.matches("[a-z0-9]+(?:-[a-z0-9]+)*"), "invalid case ID: " + id);
                require(globalIds.add(stage + ":" + id), "duplicate case ID: " + id);
                require("REVIEWED".equals(requiredText(value, "reviewStatus")),
                        "case is not reviewed: " + id);
                require(value.has("gold") && value.path("gold").isObject(), "gold label missing: " + id);
                validateStageCase(stage, value);
            }
        }
        validateMatchingSection(root.path("matchingRanking"));
    }

    private static void validateStageCase(String stage, JsonNode value) {
        JsonNode gold = value.path("gold");
        switch (stage) {
            case "candidateExtraction" -> {
                requireIsoDate(value, "assessedOn");
                requiredText(value, "text");
                requireArrays(gold, "skills", "keywords", "roleCategories", "warnings");
                requireNullableInteger(gold, "totalExperienceMonths");
                require(Set.of("KNOWN", "UNKNOWN").contains(requiredText(gold, "experienceAssessment")),
                        "invalid candidate experience state");
                requireNullableText(gold, "firstExperienceStart");
                requireNullableText(gold, "firstExperienceEnd");
                JsonNode identity = gold.path("selectedIdentity");
                require(identity.isObject(), "selected identity labels are required");
                requireNullableText(identity, "fullName");
                requireNullableText(identity, "email");
            }
            case "jobRequirementExtraction" -> {
                requiredText(value, "description");
                requireArrays(gold, "requiredSkills", "preferredSkills");
                requireNullableNumber(gold, "minimumYearsExperience");
                requireBoolean(gold, "abstain");
            }
            case "matchingRanking" -> {
                requiredText(value, "title");
                requiredText(value, "description");
                requireArrays(value, "requiredSkills", "preferredSkills");
                requireNumber(value, "minimumYearsExperience");
                requireArrays(gold, "matchedRequiredSkills", "missingRequiredSkills");
                requireNumber(gold, "overallScore");
                require(Set.of("STRONG_MATCH", "GOOD_MATCH", "WEAK_MATCH", "NOT_RECOMMENDED")
                        .contains(requiredText(gold, "recommendation")), "invalid recommendation label");
                requireInteger(gold, "relevanceGrade");
                require(gold.path("relevanceGrade").asInt() >= 0, "negative relevance grade");
            }
            case "decisionReadiness" -> {
                require(Set.of("STRONG_MATCH", "GOOD_MATCH", "WEAK_MATCH", "NOT_RECOMMENDED")
                        .contains(requiredText(value, "recommendation")), "invalid decision input recommendation");
                requireNumber(value, "score");
                require(Set.of("YES", "NO", "UNKNOWN").contains(requiredText(value, "workAuthorization")),
                        "invalid work authorization input");
                require(Set.of("YES", "NO", "UNKNOWN").contains(requiredText(value, "sponsorshipRequired")),
                        "invalid sponsorship input");
                requireBoolean(value, "preferencePresent");
                requireNullableText(value, "defaultResumeStrategy");
                requireArrays(value, "excludedRoles");
                requireBoolean(value, "identityPresent");
                requireBoolean(value, "routePresent");
                require(Set.of("SKIP", "SAVE", "APPLY_VOLUME", "APPLY_PRECISION", "NEEDS_USER")
                        .contains(requiredText(gold, "decision")), "invalid decision label");
                require(Set.of("READY", "NEEDS_USER", "NOT_READY").contains(requiredText(gold, "readiness")),
                        "invalid readiness label");
                requireBoolean(gold, "applyWorthy");
                requireBoolean(gold, "doNotApply");
                JsonNode checks = gold.path("checks");
                require(checks.isObject() && !checks.isEmpty(), "material readiness checks are required");
                checks.forEach(status -> require(status.isString()
                                && Set.of("PASS", "FAIL", "NEEDS_USER", "NOT_APPLICABLE").contains(status.asText()),
                        "invalid readiness-check label"));
            }
            default -> throw invalid("unknown evaluation stage: " + stage);
        }
    }

    private static void validateMatchingSection(JsonNode section) {
        JsonNode candidate = section.path("candidate");
        require(candidate.isObject(), "matching candidate is required");
        requireArrays(candidate, "skills", "keywords", "roleCategories");
        requireInteger(candidate, "totalExperienceMonths");
        List<String> ranking = strings(section, "goldRanking");
        Set<String> caseIds = new HashSet<>();
        section.path("cases").forEach(value -> caseIds.add(requiredText(value, "id")));
        require(ranking.size() == caseIds.size() && new HashSet<>(ranking).equals(caseIds),
                "gold ranking must contain every matching case exactly once");
        requireInteger(section, "precisionAtK");
        require(section.path("precisionAtK").asInt() > 0
                        && section.path("precisionAtK").asInt() <= caseIds.size(),
                "Precision@K is outside the dataset bounds");
    }

    private static void requireArrays(JsonNode node, String... names) {
        for (String name : names) strings(node, name);
    }

    private static void requireIsoDate(JsonNode node, String name) {
        require(requiredText(node, name).matches("\\d{4}-\\d{2}-\\d{2}"), "invalid ISO date: " + name);
    }

    private static void requireBoolean(JsonNode node, String name) {
        require(node.has(name) && node.path(name).isBoolean(), "missing boolean field: " + name);
    }

    private static void requireInteger(JsonNode node, String name) {
        require(node.has(name) && node.path(name).isIntegralNumber(), "missing integer field: " + name);
    }

    private static void requireNumber(JsonNode node, String name) {
        require(node.has(name) && node.path(name).isNumber(), "missing numeric field: " + name);
    }

    private static void requireNullableInteger(JsonNode node, String name) {
        require(node.has(name) && (node.path(name).isNull() || node.path(name).isIntegralNumber()),
                "missing nullable integer field: " + name);
    }

    private static void requireNullableNumber(JsonNode node, String name) {
        require(node.has(name) && (node.path(name).isNull() || node.path(name).isNumber()),
                "missing nullable numeric field: " + name);
    }

    private static void requireNullableText(JsonNode node, String name) {
        require(node.has(name) && (node.path(name).isNull() || node.path(name).isString()),
                "missing nullable text field: " + name);
    }

    public static String requiredText(JsonNode node, String name) {
        JsonNode value = node.path(name);
        if (!value.isString() || value.asText().isBlank()) throw invalid("missing text field: " + name);
        return value.asText();
    }

    public static List<String> strings(JsonNode node, String name) {
        JsonNode values = node.path(name);
        if (!values.isArray()) throw invalid("missing array field: " + name);
        List<String> result = new ArrayList<>();
        values.forEach(value -> {
            if (!value.isString()) throw invalid("non-text value in: " + name);
            result.add(value.asText());
        });
        return List.copyOf(result);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw invalid(message);
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
}
