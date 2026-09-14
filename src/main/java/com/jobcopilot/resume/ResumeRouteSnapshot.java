package com.jobcopilot.resume;

import com.jobcopilot.application.ResumeStrategy;
import java.time.LocalDateTime;

public record ResumeRouteSnapshot(Long id, Long candidateProfileId, Long resumeId, ResumeStrategy strategy,
        String roleFamily, boolean defaultRoute, String variantLabel, boolean approved,
        String sourceFileName, String sourceVersion, LocalDateTime createdAt) {
}
