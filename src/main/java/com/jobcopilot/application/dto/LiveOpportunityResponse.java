package com.jobcopilot.application.dto;

import com.jobcopilot.application.ApplicationDecisionResult;
import com.jobcopilot.application.ApplicationReadinessResult;
import com.jobcopilot.matching.dto.MatchResponse;
import com.jobcopilot.matching.dto.RankingJobSummary;

public record LiveOpportunityResponse(int rank, RankingJobSummary job,
        OpportunityListingSummary listing, MatchResponse match,
        ApplicationDecisionResult decision, ApplicationReadinessResult readiness) {
}
