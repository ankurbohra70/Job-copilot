package com.jobcopilot.job;

public record JobApplicationSnapshot(JobMatchingSnapshot matching, String company, String jobUrl, JobStatus status) {
}
