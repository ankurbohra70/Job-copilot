package com.jobcopilot.matching.dto;

import com.jobcopilot.matching.MatchResult;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import static com.jobcopilot.matching.MatchResult.*;

public record MatchResponse(Long candidateProfileId, Long jobId, String algorithmVersion, String vocabularyVersion,
        String profileParserVersion, LocalDate profileAssessedOn, LocalDateTime jobUpdatedAt,
        BigDecimal overallScore, Recommendation recommendation,
        List<String> matchedRequiredSkills, List<String> missingRequiredSkills,
        List<String> matchedPreferredSkills, List<String> unmatchedPreferredSkills,
        ExperienceComparison experienceComparison, Relevance roleRelevance, Relevance keywordRelevance,
        List<Breakdown> breakdown, List<Cap> appliedCaps, List<Explanation> strengths, List<Explanation> gaps,
        List<String> warnings, List<String> unassessedFactors) {
    public static MatchResponse from(Long profileId, Long jobId, String algorithm, String vocabulary, String parser,
            LocalDate assessed, LocalDateTime updated, MatchResult r) {
        return new MatchResponse(profileId, jobId, algorithm, vocabulary, parser, assessed, updated, r.overallScore(),
                r.recommendation(), r.matchedRequiredSkills(), r.missingRequiredSkills(), r.matchedPreferredSkills(),
                r.unmatchedPreferredSkills(), r.experienceComparison(), r.roleRelevance(), r.keywordRelevance(),
                r.breakdown(), r.appliedCaps(), r.strengths(), r.gaps(), r.warnings(), r.unassessedFactors());
    }
}

