package com.jobcopilot.job.dto;

import com.jobcopilot.job.JobStatus;
import jakarta.validation.constraints.NotNull;

public record UpdateJobStatusRequest(
        @NotNull(message = "must not be null")
        JobStatus status
) {
}
