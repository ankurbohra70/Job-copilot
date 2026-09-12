package com.jobcopilot.discovery.dto;

import com.jobcopilot.discovery.JobSourceProvider;
import com.jobcopilot.discovery.lever.LeverRegion;
import java.time.LocalDateTime;

public record JobSourceResponse(
        Long id,
        JobSourceProvider provider,
        LeverRegion region,
        String sourceKey,
        String companyName,
        boolean enabled,
        LocalDateTime lastSuccessfulSyncAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
