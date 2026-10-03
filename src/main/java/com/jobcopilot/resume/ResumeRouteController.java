package com.jobcopilot.resume;

import com.jobcopilot.resume.dto.ResumeRouteRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/candidate-profiles/{candidateProfileId}/resume-routes")
public class ResumeRouteController {
    private final ResumeRouteService service;
    public ResumeRouteController(ResumeRouteService service) { this.service = service; }
    @GetMapping public List<ResumeRouteSnapshot> list(@PathVariable Long candidateProfileId) { return service.list(candidateProfileId); }
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public ResumeRouteSnapshot create(@PathVariable Long candidateProfileId,
            @Valid @RequestBody ResumeRouteRequest request) { return service.create(candidateProfileId, request); }
}
