package com.jobcopilot.matching;

import com.jobcopilot.matching.dto.*;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/matches")
public class MatchController {
    private final MatchService service;
    public MatchController(MatchService service) { this.service = service; }
    @PostMapping public MatchResponse match(@Valid @RequestBody MatchRequest request) { return service.match(request); }
}

