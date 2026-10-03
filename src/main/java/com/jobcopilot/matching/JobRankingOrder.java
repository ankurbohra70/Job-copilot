package com.jobcopilot.matching;

import com.jobcopilot.job.JobRankingSnapshot;

public final class JobRankingOrder {
    private JobRankingOrder() {
    }

    public static int compare(JobRankingSnapshot leftJob, MatchResult leftResult,
            JobRankingSnapshot rightJob, MatchResult rightResult) {
        int score = rightResult.overallScore().compareTo(leftResult.overallScore());
        if (score != 0) return score;
        int created = rightJob.createdAt().compareTo(leftJob.createdAt());
        if (created != 0) return created;
        return rightJob.matching().id().compareTo(leftJob.matching().id());
    }
}
