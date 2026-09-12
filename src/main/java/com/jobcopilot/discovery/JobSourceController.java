package com.jobcopilot.discovery;

import com.jobcopilot.discovery.dto.CreateJobSourceRequest;
import com.jobcopilot.discovery.dto.ExternalJobListingPageResponse;
import com.jobcopilot.discovery.dto.ExternalJobListingResponse;
import com.jobcopilot.discovery.dto.JobSourcePageResponse;
import com.jobcopilot.discovery.dto.JobSourceResponse;
import com.jobcopilot.discovery.dto.JobSourceSyncRunPageResponse;
import com.jobcopilot.discovery.dto.JobSourceSyncRunResponse;
import com.jobcopilot.discovery.dto.UpdateJobSourceEnabledRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/job-sources")
public class JobSourceController {
    private final JobSourceService service;

    public JobSourceController(JobSourceService service) { this.service = service; }

    @PostMapping
    public ResponseEntity<JobSourceResponse> create(@Valid @RequestBody CreateJobSourceRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request));
    }

    @GetMapping
    public JobSourcePageResponse list(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) Boolean enabled) {
        return service.list(page, size, sort, enabled);
    }

    @GetMapping("/{sourceId}")
    public JobSourceResponse get(@PathVariable long sourceId) { return service.get(sourceId); }

    @PatchMapping("/{sourceId}/enabled")
    public JobSourceResponse setEnabled(@PathVariable long sourceId,
            @Valid @RequestBody UpdateJobSourceEnabledRequest request) {
        return service.setEnabled(sourceId, request.enabled());
    }

    @PostMapping("/{sourceId}/sync")
    public JobSourceSyncRunResponse synchronize(@PathVariable long sourceId) {
        return service.synchronize(sourceId);
    }

    @GetMapping("/{sourceId}/sync-runs")
    public JobSourceSyncRunPageResponse syncRuns(@PathVariable long sourceId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) JobSourceSyncStatus status) {
        return service.syncRuns(sourceId, page, size, sort, status);
    }

    @GetMapping("/{sourceId}/sync-runs/{runId}")
    public JobSourceSyncRunResponse syncRun(@PathVariable long sourceId, @PathVariable long runId) {
        return service.syncRun(sourceId, runId);
    }

    @GetMapping("/{sourceId}/listings")
    public ExternalJobListingPageResponse listings(@PathVariable long sourceId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) ListingAvailability availability,
            @RequestParam(required = false) Long jobId) {
        return service.listings(sourceId, page, size, sort, availability, jobId);
    }

    @GetMapping("/{sourceId}/listings/{listingId}")
    public ExternalJobListingResponse listing(@PathVariable long sourceId, @PathVariable long listingId) {
        return service.listing(sourceId, listingId);
    }
}
