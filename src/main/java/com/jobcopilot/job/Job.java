package com.jobcopilot.job;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

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

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

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

    LocalDateTime getCreatedAt() {
        return createdAt;
    }

    LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
