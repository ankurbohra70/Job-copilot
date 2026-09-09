package com.jobcopilot.job;

import com.jobcopilot.job.dto.JobRequirementsResponse;
import java.util.Objects;

/** Independent, non-persisting JC-005 comparison baseline; never a model input or readiness gate. */
public final class JobExtractionBaseline {
    private final JobRequirementExtractor extractor;
    public JobExtractionBaseline(JobRequirementExtractor extractor) { this.extractor = Objects.requireNonNull(extractor); }
    public enum System { JC005 }
    public enum Status { SUCCESS, UNAVAILABLE }
    public enum FailureCode { EXTRACTION_UNAVAILABLE }
    public record Result(System system, Status status, JobRequirementsResponse requirements, FailureCode failureCode) {}

    public Result capture(String description) {
        try {
            var result = extractor.extract(description);
            return new Result(System.JC005, Status.SUCCESS, new JobRequirementsResponse(
                    result.requiredSkills(), result.preferredSkills(), result.minYearsExperience()), null);
        } catch (JobRequirementExtractionException expected) {
            return new Result(System.JC005, Status.UNAVAILABLE, null, FailureCode.EXTRACTION_UNAVAILABLE);
        }
    }
}
