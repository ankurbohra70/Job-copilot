package com.jobcopilot.resume;

import com.jobcopilot.resume.dto.ResumeResponse;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.net.URI;

@RestController @RequestMapping("/api/resumes")
public class ResumeController {
    private final ResumeService service;
    private final ResumePersistenceService persistence;
    public ResumeController(ResumeService service, ResumePersistenceService persistence) { this.service = service; this.persistence = persistence; }
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ResumeResponse> upload(@RequestPart("file") MultipartFile file,
            org.springframework.web.multipart.MultipartHttpServletRequest request) {
        if (request.getMultiFileMap().values().stream().mapToInt(java.util.List::size).sum() != 1)
            throw new ResumeExceptions.InvalidResumeFileException("Upload exactly one PDF file");
        var response = service.upload(file);
        return ResponseEntity.created(URI.create("/api/resumes/" + response.id())).body(response);
    }
    @GetMapping("/{id}") public ResumeResponse get(@PathVariable Long id) { return persistence.getResume(id); }
}
