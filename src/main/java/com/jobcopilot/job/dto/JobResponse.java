package com.jobcopilot.job.dto;

import com.jobcopilot.job.JobStatus;

import java.time.LocalDateTime;

public record JobResponse(
        Long id,
        String title,
        String company,
        String location,
        String jobUrl,
        String description,
        String source,
        String externalJobId,
        JobStatus status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
