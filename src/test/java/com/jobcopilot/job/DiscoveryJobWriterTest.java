package com.jobcopilot.job;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DiscoveryJobWriterTest {
    private final JobRepository repository = mock(JobRepository.class);
    private final DiscoveryJobWriter writer = new DiscoveryJobWriter(repository, new JobRequirementExtractor());

    @ParameterizedTest
    @EnumSource(JobStatus.class)
    void providerProjectionUpdatePreservesEveryApplicationStatus(JobStatus status) {
        Job job = job("Old description");
        ReflectionTestUtils.setField(job, "status", status);

        assertTrue(writer.update(job, projection("Required: Java")));

        assertEquals(status, job.getStatus());
        assertEquals("Required: Java", job.getDescription());
        verify(repository).save(job);
    }

    @Test void processedZeroRequirementsClearsOldRequirementsAndIsNotReady() {
        Job job = job("A friendly team in a pleasant office.");
        job.replaceRequirements(List.of("java"), List.of(), null);

        var result = writer.processRequirements(job);

        var processed = assertInstanceOf(DiscoveryJobWriter.ProcessingResult.Processed.class, result);
        assertFalse(processed.requirements().rankingReady());
        assertTrue(job.requirements().requiredSkills().isEmpty());
    }

    @Test void actualFailureDoesNotMutateExistingRequirements() {
        Job job = job("Required: 100 years of experience");
        job.replaceRequirements(List.of("java"), List.of("docker"), new java.math.BigDecimal("3"));

        assertInstanceOf(DiscoveryJobWriter.ProcessingResult.Failed.class, writer.processRequirements(job));

        assertEquals(List.of("java"), job.requirements().requiredSkills());
        assertEquals(List.of("docker"), job.requirements().preferredSkills());
        verify(repository, never()).save(any());
    }

    private static Job job(String description) {
        return new Job("Engineer", "Company", "Remote", "https://jobs.example/id", description, "LEVER", "id");
    }

    private static DiscoveryJobWriter.Projection projection(String description) {
        return new DiscoveryJobWriter.Projection("Engineer", "Company", "Remote",
                "https://jobs.example/id", description, "LEVER", "id");
    }
}
