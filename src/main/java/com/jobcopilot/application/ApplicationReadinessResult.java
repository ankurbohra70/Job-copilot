package com.jobcopilot.application;

import com.jobcopilot.resume.ResumeRouteSnapshot;
import java.util.List;

public record ApplicationReadinessResult(Long jobId, Long candidateProfileId, ApplicationReadiness readiness,
        ApplicationDecisionResult decision, ResumeRouteSnapshot resumeRoute, List<ReadinessCheck> checks) {
    public ApplicationReadinessResult { checks = List.copyOf(checks); }
}
