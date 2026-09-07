package com.jobcopilot.matching;

public class JobRankingComputationException extends RuntimeException {
    public JobRankingComputationException(Long jobId, Throwable cause) {
        super("Unexpected ranking failure for job " + jobId, cause);
    }
}
