package com.jobcopilot.resume.dto;

import com.jobcopilot.application.ResumeStrategy;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ResumeRouteRequest(
        @NotNull Long resumeId,
        @NotNull ResumeStrategy strategy,
        @Size(max = 255) String roleFamily,
        boolean defaultRoute,
        @NotBlank @Size(max = 255) String variantLabel,
        boolean approved) {
}
