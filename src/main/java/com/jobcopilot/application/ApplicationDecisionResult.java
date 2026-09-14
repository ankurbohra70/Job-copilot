package com.jobcopilot.application;

import com.jobcopilot.matching.MatchResult;
import java.math.BigDecimal;
import java.util.List;

public record ApplicationDecisionResult(Long jobId, Long candidateProfileId, ApplicationDecision decision,
        List<String> reasons, BigDecimal rankingScore, MatchResult.Recommendation assessmentRecommendation,
        boolean candidateDataMissing, ResumeStrategy recommendedResumeStrategy, String policyVersion) {
    public ApplicationDecisionResult { reasons = List.copyOf(reasons); }
}
