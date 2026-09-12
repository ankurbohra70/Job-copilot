package com.jobcopilot.discovery.dto;

import com.jobcopilot.discovery.JobSourceProvider;
import com.jobcopilot.discovery.lever.LeverRegion;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateJobSourceRequest(
        @NotNull(message = "must not be null") JobSourceProvider provider,
        @NotNull(message = "must not be null") LeverRegion region,
        @NotBlank(message = "must not be blank")
        @Size(max = 100, message = "must not exceed 100 characters") String sourceKey,
        @NotBlank(message = "must not be blank")
        @Size(max = 255, message = "must not exceed 255 characters") String companyName,
        @NotNull(message = "must not be null") Boolean enabled) {
}
