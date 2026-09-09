package com.jobcopilot.matching;

import com.jobcopilot.matching.dto.JobAssessmentRequest;
import com.jobcopilot.matching.dto.MatchResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/jobs")
public class JobAssessmentController {
    private final JobAssessmentService service;

    public JobAssessmentController(JobAssessmentService service) {
        this.service = service;
    }

    @PostMapping("/{jobId}/assessment")
    public MatchResponse assess(@PathVariable Long jobId, @Valid @RequestBody JobAssessmentRequest request) {
        return service.assess(jobId, request.candidateProfileId());
    }
}
