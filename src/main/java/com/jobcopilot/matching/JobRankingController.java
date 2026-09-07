package com.jobcopilot.matching;

import com.jobcopilot.matching.dto.JobRankingResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/candidate-profiles")
public class JobRankingController {
    private final JobRankingService service;

    public JobRankingController(JobRankingService service) {
        this.service = service;
    }

    @GetMapping("/{candidateProfileId}/job-rankings")
    public JobRankingResponse rank(@PathVariable Long candidateProfileId, HttpServletRequest request) {
        return service.rank(candidateProfileId, JobRankingQuery.from(request.getParameterMap()));
    }
}
