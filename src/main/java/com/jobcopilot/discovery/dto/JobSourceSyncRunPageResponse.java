package com.jobcopilot.discovery.dto;

import java.util.List;

public record JobSourceSyncRunPageResponse(
        List<JobSourceSyncRunResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last) {
}
