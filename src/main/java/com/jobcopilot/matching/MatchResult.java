package com.jobcopilot.matching;

import java.math.BigDecimal;
import java.util.List;

public record MatchResult(BigDecimal overallScore, Recommendation recommendation,
        List<String> matchedRequiredSkills, List<String> missingRequiredSkills,
        List<String> matchedPreferredSkills, List<String> unmatchedPreferredSkills,
        ExperienceComparison experienceComparison, Relevance roleRelevance, Relevance keywordRelevance,
        List<Breakdown> breakdown, List<Cap> appliedCaps, List<Explanation> strengths, List<Explanation> gaps,
        List<String> warnings, List<String> unassessedFactors) {
    public enum Recommendation { STRONG_MATCH, GOOD_MATCH, WEAK_MATCH, NOT_RECOMMENDED }
    public enum Category { SKILLS, EXPERIENCE, ROLE, KEYWORDS }
    public enum Status { ASSESSED, UNKNOWN, NOT_APPLICABLE, MEETS_REQUIREMENT, BELOW_REQUIREMENT }
    public record ExperienceComparison(BigDecimal requiredYears, BigDecimal candidateYears, Status status, BigDecimal score) {}
    public record Relevance(Status status, BigDecimal score, List<String> matchedTerms) {
        public Relevance { matchedTerms = List.copyOf(matchedTerms); }
    }
    public record Breakdown(Category category, Status status, BigDecimal score, BigDecimal effectiveWeight, BigDecimal contribution) {}
    public record Cap(String code, BigDecimal maximumScore) {}
    public record Explanation(String code, String message, List<String> evidence) {
        public Explanation { evidence = List.copyOf(evidence); }
    }
    public MatchResult {
        matchedRequiredSkills = List.copyOf(matchedRequiredSkills); missingRequiredSkills = List.copyOf(missingRequiredSkills);
        matchedPreferredSkills = List.copyOf(matchedPreferredSkills); unmatchedPreferredSkills = List.copyOf(unmatchedPreferredSkills);
        breakdown = List.copyOf(breakdown); appliedCaps = List.copyOf(appliedCaps);
        strengths = List.copyOf(strengths); gaps = List.copyOf(gaps); warnings = List.copyOf(warnings);
        unassessedFactors = List.copyOf(unassessedFactors);
    }
}

