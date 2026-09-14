package com.jobcopilot.application;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/jobs")
public class ApplicationController {
    private final ApplicationDecisionService decisions;
    private final ApplicationReadinessService readiness;
    public ApplicationController(ApplicationDecisionService decisions, ApplicationReadinessService readiness) {
        this.decisions = decisions; this.readiness = readiness;
    }
    @GetMapping("/{jobId}/application-decision")
    public ApplicationDecisionResult decision(@PathVariable Long jobId, @RequestParam Long candidateProfileId) {
        return decisions.decide(jobId, candidateProfileId);
    }
    @GetMapping("/{jobId}/application-readiness")
    public ApplicationReadinessResult readiness(@PathVariable Long jobId, @RequestParam Long candidateProfileId) {
        return readiness.evaluate(jobId, candidateProfileId);
    }
}
