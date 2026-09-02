package com.jobcopilot.job;

import com.jobcopilot.job.dto.CreateJobRequest;
import com.jobcopilot.job.dto.JobResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class JobService {

    private final JobRepository jobRepository;

    JobService(JobRepository jobRepository) {
        this.jobRepository = jobRepository;
    }

    @Transactional
    public JobResponse createJob(CreateJobRequest request) {
        Job job = new Job(
                request.title(),
                request.company(),
                request.location(),
                request.jobUrl(),
                request.description(),
                request.source(),
                request.externalJobId()
        );

        return toResponse(jobRepository.save(job));
    }

    @Transactional(readOnly = true)
    public List<JobResponse> getJobs() {
        return jobRepository.findAll().stream()
                .map(JobService::toResponse)
                .toList();
    }

    private static JobResponse toResponse(Job job) {
        return new JobResponse(
                job.getId(),
                job.getTitle(),
                job.getCompany(),
                job.getLocation(),
                job.getJobUrl(),
                job.getDescription(),
                job.getSource(),
                job.getExternalJobId(),
                job.getCreatedAt(),
                job.getUpdatedAt()
        );
    }
}
