package com.jobcopilot.resume;

import com.jobcopilot.resume.dto.JobSearchPreferenceRequest;
import com.jobcopilot.resume.dto.JobSearchPreferenceResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/candidate-profiles/{candidateProfileId}/job-search-preference")
public class JobSearchPreferenceController {
    private final JobSearchPreferenceService service;
    public JobSearchPreferenceController(JobSearchPreferenceService service) { this.service = service; }
    @GetMapping public JobSearchPreferenceResponse get(@PathVariable Long candidateProfileId) { return service.get(candidateProfileId); }
    @PutMapping public JobSearchPreferenceResponse replace(@PathVariable Long candidateProfileId,
            @Valid @RequestBody JobSearchPreferenceRequest request) { return service.replace(candidateProfileId, request); }
}
