package com.jobcopilot.job;

import com.jobcopilot.job.dto.JobRequirementsResponse;
import java.time.LocalDateTime;

public record JobMatchingSnapshot(Long id, String title, String description, String location,
                                  JobRequirementsResponse requirements, LocalDateTime updatedAt) {}

