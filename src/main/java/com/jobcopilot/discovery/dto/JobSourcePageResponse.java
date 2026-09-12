package com.jobcopilot.discovery.dto;

import java.util.List;

public record JobSourcePageResponse(
        List<JobSourceResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last) {
}
