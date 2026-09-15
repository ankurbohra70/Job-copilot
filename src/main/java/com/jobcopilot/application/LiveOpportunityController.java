package com.jobcopilot.application;

import com.jobcopilot.application.dto.LiveOpportunityPageResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/candidate-profiles")
public class LiveOpportunityController {
    private final LiveOpportunityService service;

    public LiveOpportunityController(LiveOpportunityService service) {
        this.service = service;
    }

    @GetMapping("/{candidateProfileId}/opportunities")
    public LiveOpportunityPageResponse find(@PathVariable Long candidateProfileId, HttpServletRequest request) {
        return service.find(candidateProfileId, OpportunityQuery.from(request.getParameterMap()));
    }
}
