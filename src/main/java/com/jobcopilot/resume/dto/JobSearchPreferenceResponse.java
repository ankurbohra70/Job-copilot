package com.jobcopilot.resume.dto;

import com.jobcopilot.resume.JobSearchPreferenceData;
import java.time.LocalDateTime;

public record JobSearchPreferenceResponse(Long id, Long candidateProfileId, JobSearchPreferenceData preference,
        LocalDateTime createdAt, LocalDateTime updatedAt) {
}
