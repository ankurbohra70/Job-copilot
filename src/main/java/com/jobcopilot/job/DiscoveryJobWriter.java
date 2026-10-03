package com.jobcopilot.job;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;

/** Narrow Job-domain seam used by discovery transactions. Callers own the transaction. */
@Service
public class DiscoveryJobWriter {
    private final JobRepository jobs;
    private final JobRequirementExtractor extractor;

    DiscoveryJobWriter(JobRepository jobs, JobRequirementExtractor extractor) {
        this.jobs = jobs;
        this.extractor = extractor;
    }

    public Job create(Projection projection) {
        Objects.requireNonNull(projection, "projection is required");
        return jobs.save(new Job(projection.title(), projection.company(), projection.location(),
                projection.jobUrl(), projection.description(), projection.source(), projection.externalJobId()));
    }

    public boolean update(Job job, Projection projection) {
        Objects.requireNonNull(job, "job is required");
        Objects.requireNonNull(projection, "projection is required");
        boolean changed = !Objects.equals(job.getTitle(), projection.title())
                || !Objects.equals(job.getCompany(), projection.company())
                || !Objects.equals(job.getLocation(), projection.location())
                || !Objects.equals(job.getJobUrl(), projection.jobUrl())
                || !Objects.equals(job.getDescription(), projection.description())
                || !Objects.equals(job.getSource(), projection.source())
                || !Objects.equals(job.getExternalJobId(), projection.externalJobId());
        if (changed) {
            job.replaceDetails(projection.title(), projection.company(), projection.location(),
                    projection.jobUrl(), projection.description(), projection.source(), projection.externalJobId());
            jobs.save(job);
        }
        return changed;
    }

    public ProcessingResult processRequirements(Job job) {
        Objects.requireNonNull(job, "job is required");
        try {
            JobRequirementExtractor.Extraction extraction = extractor.process(job.getDescription());
            Requirements requirements = new Requirements(extraction.requiredSkills(), extraction.preferredSkills(),
                    extraction.minYearsExperience());
            if (!semanticallySame(job.requirements(), requirements)) {
                job.replaceRequirements(requirements.requiredSkills(), requirements.preferredSkills(),
                        requirements.minYearsExperience());
                jobs.save(job);
            }
            return new ProcessingResult.Processed(requirements);
        } catch (JobRequirementExtractionException expected) {
            return new ProcessingResult.Failed("EXTRACTION_FAILED");
        }
    }

    public String extractorVersion() {
        return extractor.version();
    }

    public boolean rankingReady(Job job) {
        var requirements = Objects.requireNonNull(job, "job is required").requirements();
        return !requirements.requiredSkills().isEmpty() || !requirements.preferredSkills().isEmpty()
                || requirements.minYearsExperience() != null && requirements.minYearsExperience().signum() > 0;
    }

    private static boolean semanticallySame(com.jobcopilot.job.dto.JobRequirementsResponse current,
            Requirements next) {
        return current.requiredSkills().equals(next.requiredSkills())
                && current.preferredSkills().equals(next.preferredSkills())
                && sameMinimum(current.minYearsExperience(), next.minYearsExperience());
    }

    private static boolean sameMinimum(BigDecimal left, BigDecimal right) {
        if (left == null || right == null) return left == right;
        return left.compareTo(right) == 0;
    }

    public record Projection(String title, String company, String location, String jobUrl,
            String description, String source, String externalJobId) {
        public Projection {
            Objects.requireNonNull(title);
            Objects.requireNonNull(company);
            Objects.requireNonNull(jobUrl);
            Objects.requireNonNull(source);
            Objects.requireNonNull(externalJobId);
        }
    }

    public record Requirements(List<String> requiredSkills, List<String> preferredSkills,
            BigDecimal minYearsExperience) {
        public Requirements {
            requiredSkills = List.copyOf(Objects.requireNonNull(requiredSkills));
            preferredSkills = List.copyOf(Objects.requireNonNull(preferredSkills));
        }

        public boolean rankingReady() {
            return !requiredSkills.isEmpty() || !preferredSkills.isEmpty()
                    || minYearsExperience != null && minYearsExperience.signum() > 0;
        }
    }

    public sealed interface ProcessingResult {
        record Processed(Requirements requirements) implements ProcessingResult {
            public Processed { Objects.requireNonNull(requirements); }
        }
        record Failed(String failureCode) implements ProcessingResult {
            public Failed { Objects.requireNonNull(failureCode); }
        }
    }
}
