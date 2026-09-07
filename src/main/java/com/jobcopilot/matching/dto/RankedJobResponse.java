package com.jobcopilot.matching.dto;

public record RankedJobResponse(int rank, RankingJobSummary job, RankingMatchSummary match) {
}
