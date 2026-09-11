package com.jobcopilot.resume;

import com.jobcopilot.resume.dto.CandidateProfileResponse;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/candidate-profiles")
public class CandidateProfileController {
    private final ResumePersistenceService persistence;
    public CandidateProfileController(ResumePersistenceService persistence) { this.persistence = persistence; }
    @GetMapping("/{id}") public CandidateProfileResponse get(@PathVariable Long id) { return persistence.getProfile(id); }
}

