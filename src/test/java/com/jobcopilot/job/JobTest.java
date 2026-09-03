package com.jobcopilot.job;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JobTest {

    @Test
    void replacingDetailsPreservesStatusAndTimestampsUntilJpaLifecycleRuns() {
        Job job = new Job("Backend Engineer", "Example", null, null, null, null, null);
        LocalDateTime createdAt = LocalDateTime.of(2026, 9, 3, 10, 30);
        LocalDateTime updatedAt = createdAt.plusMinutes(1);
        ReflectionTestUtils.setField(job, "createdAt", createdAt);
        ReflectionTestUtils.setField(job, "updatedAt", updatedAt);

        job.replaceDetails("Senior Engineer", "Updated", "Remote", null, null, null, null);

        assertEquals(JobStatus.DISCOVERED, job.getStatus());
        assertEquals(createdAt, job.getCreatedAt());
        assertEquals(updatedAt, job.getUpdatedAt());
        assertEquals("Senior Engineer", job.getTitle());
    }

    @Test
    void changingStatusDoesNotModifyJobDetailsOrCreatedAt() {
        Job job = new Job("Backend Engineer", "Example", null, null, null, null, null);
        LocalDateTime createdAt = LocalDateTime.of(2026, 9, 3, 10, 30);
        ReflectionTestUtils.setField(job, "createdAt", createdAt);

        job.changeStatus(JobStatus.APPLIED);

        assertEquals(JobStatus.APPLIED, job.getStatus());
        assertEquals("Backend Engineer", job.getTitle());
        assertEquals("Example", job.getCompany());
        assertEquals(createdAt, job.getCreatedAt());
    }
}
