package com.jobcopilot.matching.dto;

import com.jobcopilot.matching.MatchResult;

import java.math.BigDecimal;
import java.util.List;

import static com.jobcopilot.matching.MatchResult.Cap;
import static com.jobcopilot.matching.MatchResult.ExperienceComparison;
import static com.jobcopilot.matching.MatchResult.Explanation;
import static com.jobcopilot.matching.MatchResult.Recommendation;

public record RankingMatchSummary(
        BigDecimal overallScore,
        Recommendation recommendation,
        List<String> matchedRequiredSkills,
        List<String> missingRequiredSkills,
        List<String> matchedPreferredSkills,
        List<String> unmatchedPreferredSkills,
        ExperienceComparison experienceComparison,
        List<Cap> appliedCaps,
        List<Explanation> strengths,
        List<Explanation> gaps,
        List<String> warnings,
        List<String> unassessedFactors
) {
    public RankingMatchSummary {
        matchedRequiredSkills = List.copyOf(matchedRequiredSkills);
        missingRequiredSkills = List.copyOf(missingRequiredSkills);
        matchedPreferredSkills = List.copyOf(matchedPreferredSkills);
        unmatchedPreferredSkills = List.copyOf(unmatchedPreferredSkills);
        appliedCaps = List.copyOf(appliedCaps);
        strengths = List.copyOf(strengths);
        gaps = List.copyOf(gaps);
        warnings = List.copyOf(warnings);
        unassessedFactors = List.copyOf(unassessedFactors);
    }

    public static RankingMatchSummary from(MatchResult result) {
        return new RankingMatchSummary(
                result.overallScore(),
                result.recommendation(),
                result.matchedRequiredSkills(),
                result.missingRequiredSkills(),
                result.matchedPreferredSkills(),
                result.unmatchedPreferredSkills(),
                result.experienceComparison(),
                result.appliedCaps(),
                result.strengths(),
                result.gaps(),
                result.warnings(),
                result.unassessedFactors()
        );
    }
}
