package com.jobcopilot.discovery.dto;

import jakarta.validation.constraints.NotNull;

public record UpdateJobSourceEnabledRequest(
        @NotNull(message = "must not be null") Boolean enabled) {
}
