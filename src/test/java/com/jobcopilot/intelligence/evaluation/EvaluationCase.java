package com.jobcopilot.intelligence.evaluation;

import com.jobcopilot.intelligence.JobIntelligence;
import com.jobcopilot.intelligence.JobIntelligencePrompt;
import java.math.BigDecimal;
import java.util.List;

record EvaluationDataset(
        String datasetVersion,
        String scriptedResponseVersion,
        String matchingVocabularyVersion,
        List<EvaluationCase> cases) {
    EvaluationDataset { cases = immutable(cases); }
    private static <T> List<T> immutable(List<T> values) { return values == null ? null : List.copyOf(values); }
}

record EvaluationCase(
        String id,
        List<String> tags,
        String reviewStatus,
        String title,
        String description,
        JobIntelligencePrompt.CanonicalRequirements canonicalRequirements,
        GoldTruth gold) {
    EvaluationCase { tags = tags == null ? null : List.copyOf(tags); }
}

record GoldTruth(
        List<GoldSkill> skills,
        List<GoldQualification> qualifications,
        List<GoldExperienceClause> experienceClauses,
        GoldMinimumExperience minimumExperience) {
    GoldTruth {
        skills = skills == null ? null : List.copyOf(skills);
        qualifications = qualifications == null ? null : List.copyOf(qualifications);
        experienceClauses = experienceClauses == null ? null : List.copyOf(experienceClauses);
    }

    boolean hasAffirmativeFacts() {
        return !skills.isEmpty() || !qualifications.isEmpty() || !experienceClauses.isEmpty();
    }
}

record GoldSkill(String name, JobIntelligence.Importance importance, List<EvidenceAnchor> support) {
    GoldSkill { support = support == null ? null : List.copyOf(support); }
}

record GoldQualification(
        String id,
        List<String> acceptedTexts,
        JobIntelligence.Importance importance,
        List<EvidenceAnchor> support) {
    GoldQualification {
        acceptedTexts = acceptedTexts == null ? null : List.copyOf(acceptedTexts);
        support = support == null ? null : List.copyOf(support);
    }
}

record GoldExperienceClause(
        String id,
        BigDecimal minimum,
        JobIntelligence.Unit unit,
        JobIntelligence.Importance importance,
        JobIntelligence.Scope scope,
        boolean conditional,
        List<EvidenceAnchor> support) {
    GoldExperienceClause { support = support == null ? null : List.copyOf(support); }
}

record GoldMinimumExperience(JobIntelligence.MinimumStatus status, BigDecimal months) {}

record EvidenceAnchor(JobIntelligence.Source source, String quote, int occurrence) {}
