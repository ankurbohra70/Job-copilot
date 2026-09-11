package com.jobcopilot.job;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JobTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"2.345", "80.001", "80.004", "-2", "100"})
    void invalidInternalMinimumFailsBeforeAnyMutation(String value) {
        Job job = new Job("Role", "Company", null, null, null, null, null);
        job.replaceRequirements(java.util.List.of("redis"), java.util.List.of("docker"), new java.math.BigDecimal("3"));
        var before = job.requirements();
        var timestamp = job.getUpdatedAt();
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () ->
                job.replaceRequirements(java.util.List.of("java"), java.util.List.of(), new java.math.BigDecimal(value)));
        assertEquals(before, job.requirements());
        assertEquals(timestamp, job.getUpdatedAt());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"0", "0.00", "0.01", "1", "1.5", "2", "2.00", "79.99", "80", "80.00"})
    void validMinimumIsNormalizedWithoutRounding(String value) {
        assertEquals(new java.math.BigDecimal(value).setScale(2), Job.canonicalExperience(new java.math.BigDecimal(value)));
    }
    @Test
    void requirementsAreDefensiveAndPreserveCoreState() {
        Job job = new Job("Backend Engineer", "Example", null, null, null, null, null);
        var required = new java.util.ArrayList<>(java.util.List.of("java"));
        job.replaceRequirements(required, java.util.List.of(), java.math.BigDecimal.ONE);
        required.clear();
        assertEquals(java.util.List.of("java"), job.requirements().requiredSkills());
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class, () -> job.requirements().requiredSkills().add("python"));
        assertEquals(JobStatus.DISCOVERED, job.getStatus());
        assertEquals("Backend Engineer", job.getTitle());
    }

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
