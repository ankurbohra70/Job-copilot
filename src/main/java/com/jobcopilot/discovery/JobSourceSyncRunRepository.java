package com.jobcopilot.discovery;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

interface JobSourceSyncRunRepository extends JpaRepository<JobSourceSyncRun, Long> {
    Optional<JobSourceSyncRun> findByJobSourceAndStatus(JobSource jobSource, JobSourceSyncStatus status);
}
