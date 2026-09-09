package com.jobcopilot.job;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JobExtractionBaselineTest {
    private final JobRequirementExtractor extractor = new JobRequirementExtractor();
    private final JobExtractionBaseline baseline = new JobExtractionBaseline(extractor);

    @ParameterizedTest
    @ValueSource(strings = {"Must have Java. Preferred Docker. Minimum 2 years experience", "Preferred: Redis", "Minimum 3 years experience", "Requirements:\nJava\nPreferred:\nJava Docker"})
    void successfulBaselineExactlyMatchesExistingExtractor(String description) {
        var expected = extractor.extract(description);
        var actual = baseline.capture(description);
        assertEquals(JobExtractionBaseline.System.JC005, actual.system());
        assertEquals(JobExtractionBaseline.Status.SUCCESS, actual.status());
        assertEquals(expected.requiredSkills(), actual.requirements().requiredSkills());
        assertEquals(expected.preferredSkills(), actual.requirements().preferredSkills());
        assertEquals(expected.minYearsExperience(), actual.requirements().minYearsExperience());
        assertNull(actual.failureCode());
        assertEquals(actual, baseline.capture(description));
    }

    @ParameterizedTest @NullAndEmptySource
    @ValueSource(strings = {" ", "No recognizable requirements", "Minimum 100 years experience", "Minimum -2 years experience"})
    void expectedInabilityHasBoundedSafeCode(String description) {
        var result = baseline.capture(description);
        assertEquals(JobExtractionBaseline.Status.UNAVAILABLE, result.status());
        assertNull(result.requirements());
        assertEquals(JobExtractionBaseline.FailureCode.EXTRACTION_UNAVAILABLE, result.failureCode());
    }

    @Test void unexpectedProgrammingDefectsPropagateUnchanged() {
        var mocked = mock(JobRequirementExtractor.class);
        var defect = new IllegalStateException("unexpected defect");
        when(mocked.extract("source")).thenThrow(defect);
        assertSame(defect, assertThrows(IllegalStateException.class, () -> new JobExtractionBaseline(mocked).capture("source")));
        verify(mocked).extract("source");
        verifyNoMoreInteractions(mocked);
    }

    @Test void expectedExceptionTextNeverEscapes() {
        var mocked = mock(JobRequirementExtractor.class);
        when(mocked.extract("source")).thenThrow(new JobRequirementExtractionException("sensitive source text"));
        assertFalse(new JobExtractionBaseline(mocked).capture("source").toString().contains("sensitive"));
    }
}
