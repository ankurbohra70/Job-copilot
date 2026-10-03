package com.jobcopilot.application.dto;

import com.jobcopilot.matching.dto.UnassessedJobResponse;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record LiveOpportunityPageResponse(
        Long candidateProfileId,
        long profileRevision,
        String algorithmVersion,
        String vocabularyVersion,
        String profileParserVersion,
        LocalDate profileAssessedOn,
        Long preferenceId,
        LocalDateTime preferenceUpdatedAt,
        int liveListingCount,
        int eligibleListingCount,
        int ineligibleListingCount,
        int computableJobCount,
        int unassessedJobCount,
        int excludedJobCount,
        int filteredJobCount,
        int pageResultCount,
        int page,
        int size,
        int totalPages,
        boolean first,
        boolean last,
        List<LiveOpportunityResponse> opportunities,
        List<UnassessedJobResponse> unassessedJobs) {
    public LiveOpportunityPageResponse {
        opportunities = List.copyOf(opportunities);
        unassessedJobs = List.copyOf(unassessedJobs);
    }
}
