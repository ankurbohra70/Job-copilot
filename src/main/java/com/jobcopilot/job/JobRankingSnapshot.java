package com.jobcopilot.job;

import java.time.LocalDateTime;

public record JobRankingSnapshot(
        JobMatchingSnapshot matching,
        String company,
        String jobUrl,
        JobStatus status,
        LocalDateTime createdAt
) {
}
