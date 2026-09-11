package com.jobcopilot.matching.dto;

import java.time.LocalDate;
import java.util.List;

public record JobRankingResponse(
        Long candidateProfileId,
        String algorithmVersion,
        String vocabularyVersion,
        String profileParserVersion,
        LocalDate profileAssessedOn,
        int evaluatedJobCount,
        int computableJobCount,
        int unassessedJobCount,
        int filteredJobCount,
        int pageResultCount,
        int page,
        int size,
        int totalPages,
        boolean first,
        boolean last,
        List<RankedJobResponse> rankedJobs,
        List<UnassessedJobResponse> unassessedJobs
) {
    public JobRankingResponse {
        rankedJobs = List.copyOf(rankedJobs);
        unassessedJobs = List.copyOf(unassessedJobs);
    }

    public static JobRankingResponse of(
            Long candidateProfileId,
            String algorithmVersion,
            String vocabularyVersion,
            String profileParserVersion,
            LocalDate profileAssessedOn,
            int evaluatedJobCount,
            int computableJobCount,
            int filteredJobCount,
            int page,
            int size,
            List<RankedJobResponse> rankedJobs,
            List<UnassessedJobResponse> unassessedJobs
    ) {
        int unassessedJobCount = unassessedJobs.size();
        int pageResultCount = rankedJobs.size();
        int totalPages = filteredJobCount == 0 ? 0 : (int) ((filteredJobCount + (long) size - 1L) / size);
        boolean first = page == 0;
        boolean last = (long) page + 1L >= totalPages;
        return new JobRankingResponse(
                candidateProfileId,
                algorithmVersion,
                vocabularyVersion,
                profileParserVersion,
                profileAssessedOn,
                evaluatedJobCount,
                computableJobCount,
                unassessedJobCount,
                filteredJobCount,
                pageResultCount,
                page,
                size,
                totalPages,
                first,
                last,
                rankedJobs,
                unassessedJobs
        );
    }
}
