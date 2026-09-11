package com.jobcopilot.matching.dto;

public record UnassessedJobResponse(RankingJobSummary job, Reason reason, String message) {
    public enum Reason { INSUFFICIENT_JOB_REQUIREMENTS }
}
