package com.jobcopilot.job;

import com.jobcopilot.job.dto.CreateJobRequest;
import com.jobcopilot.job.dto.JobPageResponse;
import com.jobcopilot.job.dto.JobResponse;
import com.jobcopilot.job.dto.UpdateJobRequest;
import com.jobcopilot.job.dto.UpdateJobStatusRequest;
import com.jobcopilot.job.dto.JobRequirementsRequest;
import com.jobcopilot.job.dto.JobRequirementsResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/jobs")
public class JobController {

    private final JobService jobService;

    @GetMapping("/{id}/requirements")
    public JobRequirementsResponse getRequirements(@PathVariable Long id) { return jobService.getRequirements(id); }

    @PutMapping("/{id}/requirements")
    public JobRequirementsResponse replaceRequirements(@PathVariable Long id, @Valid @RequestBody JobRequirementsRequest request) {
        return jobService.replaceRequirements(id, request);
    }

    @PostMapping("/{id}/requirements/extract")
    public JobRequirementsResponse extractRequirements(@PathVariable Long id) {
        return jobService.extractRequirements(id);
    }

    public JobController(JobService jobService) {
        this.jobService = jobService;
    }

    @PostMapping
    public ResponseEntity<JobResponse> createJob(@Valid @RequestBody CreateJobRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(jobService.createJob(request));
    }

    @GetMapping
    public JobPageResponse getJobs(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) JobStatus status
    ) {
        return jobService.getJobs(page, size, sort, q, status);
    }

    @GetMapping("/{id}")
    public JobResponse getJob(@PathVariable Long id) {
        return jobService.getJob(id);
    }

    @PutMapping("/{id}")
    public JobResponse updateJob(@PathVariable Long id, @Valid @RequestBody UpdateJobRequest request) {
        return jobService.updateJob(id, request);
    }

    @PatchMapping("/{id}/status")
    public JobResponse updateJobStatus(
            @PathVariable Long id,
            @Valid @RequestBody UpdateJobStatusRequest request
    ) {
        return jobService.updateJobStatus(id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteJob(@PathVariable Long id) {
        jobService.deleteJob(id);
        return ResponseEntity.noContent().build();
    }
}
