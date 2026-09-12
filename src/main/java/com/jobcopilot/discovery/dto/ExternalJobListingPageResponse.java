package com.jobcopilot.discovery.dto;

import java.util.List;

public record ExternalJobListingPageResponse(
        List<ExternalJobListingResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last) {
}
