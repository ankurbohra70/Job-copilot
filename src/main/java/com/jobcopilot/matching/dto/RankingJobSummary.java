package com.jobcopilot.matching.dto;

import com.jobcopilot.job.JobRankingSnapshot;
import com.jobcopilot.job.JobStatus;

import java.time.LocalDateTime;

public record RankingJobSummary(
        Long id,
        String title,
        String company,
        String location,
        String jobUrl,
        JobStatus status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static RankingJobSummary from(JobRankingSnapshot snapshot) {
        return new RankingJobSummary(
                snapshot.matching().id(),
                snapshot.matching().title(),
                snapshot.company(),
                snapshot.matching().location(),
                snapshot.jobUrl(),
                snapshot.status(),
                snapshot.createdAt(),
                snapshot.matching().updatedAt()
        );
    }
}
