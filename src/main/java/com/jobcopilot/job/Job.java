package com.jobcopilot.job;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.JoinColumn;
import com.jobcopilot.job.dto.JobRequirementsResponse;

@Entity
@Table(name = "jobs")
class Job {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(nullable = false, length = 255)
    private String company;

    @Column(length = 255)
    private String location;

    @Column(name = "job_url", length = 2048)
    private String jobUrl;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(length = 100)
    private String source;

    @Column(name = "external_job_id", length = 255)
    private String externalJobId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32, columnDefinition = "varchar(32) default 'DISCOVERED'")
    private JobStatus status = JobStatus.DISCOVERED;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "min_years_experience", precision = 4, scale = 2)
    private BigDecimal minYearsExperience;

    @ElementCollection
    @CollectionTable(name = "job_skills", joinColumns = @JoinColumn(name = "job_id"))
    private Set<JobSkill> skills = new HashSet<>();

    void replaceRequirements(List<String> required, List<String> preferred, BigDecimal minimum) {
        BigDecimal normalized = canonicalExperience(minimum);
        skills.clear();
        required.forEach(skill -> skills.add(new JobSkill(skill, JobSkill.Importance.REQUIRED)));
        preferred.forEach(skill -> skills.add(new JobSkill(skill, JobSkill.Importance.PREFERRED)));
        minYearsExperience = normalized;
        // A collection-only change must dirty the parent as well.
        updatedAt = LocalDateTime.now();
    }

    JobRequirementsResponse requirements() {
        return new JobRequirementsResponse(
                skills.stream().filter(s -> s.importance() == JobSkill.Importance.REQUIRED).map(JobSkill::skill).sorted().toList(),
                skills.stream().filter(s -> s.importance() == JobSkill.Importance.PREFERRED).map(JobSkill::skill).sorted().toList(),
                minYearsExperience);
    }

    protected Job() {
    }

    Job(
            String title,
            String company,
            String location,
            String jobUrl,
            String description,
            String source,
            String externalJobId
    ) {
        this.title = title;
        this.company = company;
        this.location = location;
        this.jobUrl = jobUrl;
        this.description = description;
        this.source = source;
        this.externalJobId = externalJobId;
    }

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    Long getId() {
        return id;
    }

    String getTitle() {
        return title;
    }

    String getCompany() {
        return company;
    }

    String getLocation() {
        return location;
    }

    String getJobUrl() {
        return jobUrl;
    }

    String getDescription() {
        return description;
    }

    String getSource() {
        return source;
    }

    String getExternalJobId() {
        return externalJobId;
    }

    JobStatus getStatus() {
        return status;
    }

    LocalDateTime getCreatedAt() {
        return createdAt;
    }

    LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    void replaceDetails(
            String title,
            String company,
            String location,
            String jobUrl,
            String description,
            String source,
            String externalJobId
    ) {
        this.title = title;
        this.company = company;
        this.location = location;
        this.jobUrl = jobUrl;
        this.description = description;
        this.source = source;
        this.externalJobId = externalJobId;
    }

    void changeStatus(JobStatus status) {
        this.status = status;
    }

    static BigDecimal canonicalExperience(BigDecimal minimum) {
        if (minimum == null) {
            return null;
        }
        validateExperience(minimum);
        return minimum.setScale(2, RoundingMode.UNNECESSARY);
    }

    static void validateExperience(BigDecimal minimum) {
        if (minimum == null) return;
        if (minimum.signum() < 0 || minimum.compareTo(BigDecimal.valueOf(80)) > 0)
            throw new IllegalArgumentException("Experience must be between 0 and 80");
        if (minimum.scale() > 2)
            throw new IllegalArgumentException("Experience must have at most two decimal places");
    }
}
