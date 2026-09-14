package com.jobcopilot.resume;

import com.jobcopilot.resume.dto.CandidateProfileResponse;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;

@RestController @RequestMapping("/api/candidate-profiles")
public class CandidateProfileController {
    private final ResumePersistenceService persistence;
    public CandidateProfileController(ResumePersistenceService persistence) { this.persistence = persistence; }
    @GetMapping("/{id}") public CandidateProfileResponse get(@PathVariable Long id) { return persistence.getProfile(id); }
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public CandidateProfileResponse create(@Valid @RequestBody com.jobcopilot.resume.dto.CandidateProfileRequest request) {
        return persistence.createProfile(request);
    }
    @PutMapping("/{id}")
    public CandidateProfileResponse update(@PathVariable Long id,
            @Valid @RequestBody com.jobcopilot.resume.dto.CandidateProfileRequest request) {
        return persistence.updateProfile(id, request);
    }
}

