package com.jobcopilot.job.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateJobRequest(
        @NotBlank(message = "must not be blank")
        @Size(max = 255, message = "must not exceed 255 characters")
        String title,

        @NotBlank(message = "must not be blank")
        @Size(max = 255, message = "must not exceed 255 characters")
        String company,

        @Size(max = 255, message = "must not exceed 255 characters")
        String location,

        @Size(max = 2048, message = "must not exceed 2048 characters")
        String jobUrl,

        String description,

        @Size(max = 100, message = "must not exceed 100 characters")
        String source,

        @Size(max = 255, message = "must not exceed 255 characters")
        String externalJobId
) {
}
