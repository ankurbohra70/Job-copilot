package com.jobcopilot.job;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JobRequirementExtractorPhase3SeamTest {
    private final JobRequirementExtractor extractor = new JobRequirementExtractor();

    @Test void exposesStableVersionAndProcessesUsefulRequirements() {
        assertEquals("jc005-v1", extractor.version());
        var result = extractor.process("Required: Java\nMinimum 3 years of experience");
        assertEquals(java.util.List.of("java"), result.requiredSkills());
        assertEquals(0, result.minYearsExperience().compareTo(new java.math.BigDecimal("3")));
    }

    @Test void internalProcessingTreatsZeroRequirementsAsSuccessfulButPublicApiStillRejectsIt() {
        var result = extractor.process("A friendly team with a pleasant office.");
        assertTrue(result.requiredSkills().isEmpty());
        assertTrue(result.preferredSkills().isEmpty());
        assertNull(result.minYearsExperience());
        assertThrows(JobRequirementExtractionException.class,
                () -> extractor.extract("A friendly team with a pleasant office."));
    }

    @Test void actualInvalidExtractionStillFails() {
        assertThrows(JobRequirementExtractionException.class,
                () -> extractor.process("Required: 100 years of experience"));
    }
}
