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
        int rankedJobCount,
        int unassessedJobCount,
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
            List<RankedJobResponse> rankedJobs,
            List<UnassessedJobResponse> unassessedJobs
    ) {
        return new JobRankingResponse(
                candidateProfileId,
                algorithmVersion,
                vocabularyVersion,
                profileParserVersion,
                profileAssessedOn,
                rankedJobs.size() + unassessedJobs.size(),
                rankedJobs.size(),
                unassessedJobs.size(),
                rankedJobs,
                unassessedJobs
        );
    }
}
