package com.jobcopilot.intelligence.evaluation;

import com.jobcopilot.common.text.MatchingVocabulary;
import com.jobcopilot.intelligence.JobIntelligence;
import com.jobcopilot.intelligence.JobIntelligenceModel;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;

final class EvaluationDatasetLoader {
    static final String DATASET_VERSION = "jc007-evaluation-v1";
    static final String RESPONSE_VERSION = "jc007-scripted-v1";
    static final int CASE_COUNT = 24;

    private static final Set<String> TAGS = Set.of(
            "skills", "required", "preferred", "multi-skill", "framework-overlap", "aliases",
            "symbols", "negation", "optionality", "title-only", "markdown", "bullets", "dense-prose",
            "experience", "numeric-years", "range", "decimal", "months", "multiple-experience",
            "conditional", "overall", "relevant", "skill-specific", "unrelated-duration",
            "qualification", "repeated-evidence", "ambiguous", "unsupported-claim", "sparse",
            "canonical-empty", "canonical-accurate", "canonical-partial", "canonical-stale",
            "canonical-manual");
    private static final Set<String> REQUIRED_COVERAGE = Set.of(
            "skills", "required", "preferred", "multi-skill", "framework-overlap", "aliases", "symbols",
            "negation", "optionality", "title-only", "markdown", "bullets", "dense-prose", "experience",
            "numeric-years", "range", "decimal", "months", "multiple-experience", "conditional", "overall",
            "relevant", "skill-specific", "unrelated-duration", "qualification", "repeated-evidence",
            "ambiguous", "unsupported-claim", "sparse", "canonical-empty", "canonical-accurate",
            "canonical-partial", "canonical-stale", "canonical-manual");

    private final JsonMapper json = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(MapperFeature.REQUIRE_SETTERS_FOR_GETTERS)
            .build();
    private final MatchingVocabulary vocabulary = MatchingVocabulary.standard();

    LoadedEvaluation load() {
        try (InputStream cases = resource("cases.json"); InputStream responses = resource("scripted-responses.json")) {
            return load(cases, responses);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot close evaluation resources", failure);
        }
    }

    LoadedEvaluation load(InputStream caseInput, InputStream responseInput) {
        if (caseInput == null || responseInput == null) throw invalid("missing evaluation resource");
        try {
            EvaluationDataset dataset = json.readValue(caseInput, EvaluationDataset.class);
            ScriptedResponseSet responses = json.readValue(responseInput, ScriptedResponseSet.class);
            return validate(dataset, responses);
        } catch (RuntimeException failure) {
            if (failure instanceof IllegalArgumentException invalid) throw invalid;
            throw invalid("malformed evaluation resource", failure);
        }
    }

    LoadedEvaluation load(String cases, String responses) {
        return load(new java.io.ByteArrayInputStream(cases.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                new java.io.ByteArrayInputStream(responses.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private LoadedEvaluation validate(EvaluationDataset dataset, ScriptedResponseSet responses) {
        require(dataset != null, "dataset is required");
        require(DATASET_VERSION.equals(dataset.datasetVersion()), "invalid dataset version");
        require(RESPONSE_VERSION.equals(dataset.scriptedResponseVersion()), "invalid dataset response version");
        require(vocabulary.version().equals(dataset.matchingVocabularyVersion()), "matching vocabulary version mismatch");
        require(dataset.cases() != null && dataset.cases().size() == CASE_COUNT, "dataset must contain exactly 24 cases");
        Set<String> caseIds = new HashSet<>();
        Set<String> covered = new HashSet<>();
        List<EvaluationCase> sorted = new ArrayList<>(dataset.cases());
        for (EvaluationCase evaluationCase : sorted) {
            validateCase(evaluationCase, caseIds, covered);
        }
        require(covered.containsAll(REQUIRED_COVERAGE), "dataset tag coverage is incomplete");
        sorted.sort(java.util.Comparator.comparing(EvaluationCase::id));

        require(responses != null && RESPONSE_VERSION.equals(responses.scriptedResponseVersion()),
                "invalid scripted response version");
        require(responses.cases() != null && responses.cases().size() == CASE_COUNT,
                "scripted responses must contain exactly 24 cases");
        Set<String> responseIds = new HashSet<>();
        for (ScriptedCaseResponse response : responses.cases()) {
            require(response != null && response.caseId() != null && responseIds.add(response.caseId()),
                    "duplicate or missing scripted response case ID");
            require(caseIds.contains(response.caseId()), "scripted response references unknown case");
            validateAttempt(response.hybrid(), "hybrid", false);
            validateAttempt(response.llmFirst(), "llmFirst", true);
            require(response.llmFirst().candidateRef() == null
                            || response.hybrid().outcome() == JobIntelligenceModel.Outcome.COMPLETED,
                    "llmFirst candidate reference requires a completed hybrid candidate");
        }
        require(responseIds.equals(caseIds), "missing scripted response");
        List<ScriptedCaseResponse> sortedResponses = new ArrayList<>(responses.cases());
        sortedResponses.sort(java.util.Comparator.comparing(ScriptedCaseResponse::caseId));
        return new LoadedEvaluation(new EvaluationDataset(dataset.datasetVersion(), dataset.scriptedResponseVersion(),
                dataset.matchingVocabularyVersion(), sorted),
                new ScriptedResponseSet(responses.scriptedResponseVersion(), sortedResponses));
    }

    private void validateCase(EvaluationCase value, Set<String> ids, Set<String> covered) {
        require(value != null && value.id() != null && value.id().matches("[a-z0-9]+(?:-[a-z0-9]+)*"),
                "invalid case ID");
        require(ids.add(value.id()), "duplicate case ID");
        require("REVIEWED".equals(value.reviewStatus()), "case is not manually reviewed");
        require(value.tags() != null && !value.tags().isEmpty(), "case tags are required");
        require(value.tags().stream().allMatch(TAGS::contains), "unknown evaluation tag");
        require(new LinkedHashSet<>(value.tags()).size() == value.tags().size(), "duplicate evaluation tag");
        covered.addAll(value.tags());
        require(value.title() != null && value.description() != null, "raw title and description are required");
        require(value.canonicalRequirements() != null, "hybrid canonical requirements are required");
        require(value.canonicalRequirements().requiredSkills() != null
                && value.canonicalRequirements().preferredSkills() != null, "canonical skill lists are required");
        require(value.gold() != null && value.gold().skills() != null && value.gold().qualifications() != null
                && value.gold().experienceClauses() != null && value.gold().minimumExperience() != null,
                "gold truth is incomplete");
        validateGold(value);
    }

    private void validateGold(EvaluationCase value) {
        Set<String> skillNames = new HashSet<>();
        Set<String> skillKeys = new HashSet<>();
        for (GoldSkill skill : value.gold().skills()) {
            require(skill != null && skill.name() != null && !skill.name().isBlank() && skill.importance() != null,
                    "invalid gold skill");
            String name = vocabulary.canonical(skill.name());
            require(skillKeys.add(name + "\u0000" + skill.importance()), "duplicate gold skill");
            require(skillNames.add(name), "cross-class gold skill conflict");
            validateSupport(value, skill.support());
        }
        Set<String> qualificationIds = new HashSet<>();
        Set<String> qualificationTexts = new HashSet<>();
        for (GoldQualification qualification : value.gold().qualifications()) {
            require(qualification != null && validAnnotationId(qualification.id()) && qualificationIds.add(qualification.id()),
                    "duplicate or invalid gold qualification ID");
            require(qualification.importance() != null && qualification.acceptedTexts() != null
                    && !qualification.acceptedTexts().isEmpty(), "invalid gold qualification");
            for (String accepted : qualification.acceptedTexts()) {
                require(accepted != null && !accepted.isBlank(), "invalid qualification accepted text");
                require(qualificationTexts.add(normalize(accepted)), "ambiguous qualification accepted text");
            }
            validateSupport(value, qualification.support());
        }
        Set<String> experienceIds = new HashSet<>();
        for (GoldExperienceClause clause : value.gold().experienceClauses()) {
            require(clause != null && validAnnotationId(clause.id()) && experienceIds.add(clause.id()),
                    "duplicate or invalid gold experience ID");
            require(clause.importance() != null && clause.scope() != null, "invalid gold experience classification");
            require((clause.minimum() == null) == (clause.unit() == null), "gold experience quantity/unit mismatch");
            require(clause.minimum() == null || clause.minimum().signum() >= 0, "negative gold experience");
            validateSupport(value, clause.support());
        }
        GoldMinimumExperience minimum = value.gold().minimumExperience();
        require(minimum.status() != null, "gold minimum status is required");
        require(minimum.status() == JobIntelligence.MinimumStatus.KNOWN
                        ? minimum.months() != null && minimum.months().signum() >= 0
                        : minimum.months() == null,
                "invalid gold aggregate minimum");
    }

    private void validateSupport(EvaluationCase value, List<EvidenceAnchor> support) {
        require(support != null && !support.isEmpty(), "gold claim support is required");
        for (EvidenceAnchor anchor : support) {
            require(anchor != null && anchor.source() != null && anchor.quote() != null && !anchor.quote().isEmpty(),
                    "invalid evidence anchor");
            require(anchor.occurrence() > 0, "evidence occurrence must be positive");
            String source = anchor.source() == JobIntelligence.Source.TITLE ? value.title() : value.description();
            require(occurrences(source, anchor.quote()) >= anchor.occurrence(), "evidence occurrence does not exist");
        }
    }

    private static void validateAttempt(ConfiguredAttempt attempt, String strategy, boolean allowReference) {
        require(attempt != null && attempt.outcome() != null, "missing " + strategy + " attempt");
        boolean completed = attempt.outcome() == JobIntelligenceModel.Outcome.COMPLETED;
        boolean candidateObject = attempt.candidate() != null && !attempt.candidate().isNull();
        boolean candidateText = attempt.candidateJson() != null;
        boolean candidateReference = attempt.candidateRef() != null;
        require(!candidateReference || allowReference && "HYBRID".equals(attempt.candidateRef()),
                "invalid candidate reference");
        require(!completed || (candidateObject ? 1 : 0) + (candidateText ? 1 : 0) + (candidateReference ? 1 : 0) == 1,
                "completed attempt must define exactly one candidate");
        require(completed || !candidateObject && !candidateText && !candidateReference,
                "failed attempt cannot define a candidate");
    }

    private InputStream resource(String name) {
        InputStream stream = getClass().getResourceAsStream("/job-intelligence/evaluation/v1/" + name);
        if (stream == null) throw invalid("missing evaluation resource: " + name);
        return stream;
    }

    private static int occurrences(String source, String quote) {
        int count = 0;
        for (int from = 0, at; (at = source.indexOf(quote, from)) >= 0; from = at + 1) count++;
        return count;
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ").trim();
    }

    private static boolean validAnnotationId(String value) {
        return value != null && value.matches("[a-z0-9]+(?:-[a-z0-9]+)*");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw invalid(message);
    }
    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
    private static IllegalArgumentException invalid(String message, Throwable cause) {
        return new IllegalArgumentException(message, cause);
    }
}

record LoadedEvaluation(EvaluationDataset dataset, ScriptedResponseSet responses) {}
record ScriptedResponseSet(String scriptedResponseVersion, List<ScriptedCaseResponse> cases) {
    ScriptedResponseSet { cases = cases == null ? null : List.copyOf(cases); }
}
record ScriptedCaseResponse(String caseId, ConfiguredAttempt hybrid, ConfiguredAttempt llmFirst) {}
record ConfiguredAttempt(JobIntelligenceModel.Outcome outcome, String failureCode, JsonNode candidate,
        String candidateJson, String candidateRef) {}
