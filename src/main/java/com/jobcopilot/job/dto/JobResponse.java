package com.jobcopilot.job.dto;

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
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
