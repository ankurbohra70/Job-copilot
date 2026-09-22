package com.jobcopilot.discovery.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.jobcopilot.discovery.JobSourceProvider;
import com.jobcopilot.discovery.JobSourceRegion;
import java.time.LocalDateTime;

public record JobSourceResponse(
        Long id,
        JobSourceProvider provider,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        JobSourceRegion region,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String sourceKey,
        String companyName,
        boolean enabled,
        LocalDateTime lastSuccessfulSyncAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String endpointHost,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String tenantKey,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String siteKey,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String locale) {

    public JobSourceResponse(Long id, JobSourceProvider provider,
            com.jobcopilot.discovery.lever.LeverRegion region, String sourceKey, String companyName,
            boolean enabled, LocalDateTime lastSuccessfulSyncAt, LocalDateTime createdAt,
            LocalDateTime updatedAt) {
        this(id, provider, region == null ? null : JobSourceRegion.valueOf(region.name()), sourceKey,
                companyName, enabled, lastSuccessfulSyncAt, createdAt, updatedAt,
                null, null, null, null);
    }
}
