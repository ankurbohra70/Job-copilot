package com.jobcopilot.discovery;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.List;

interface JobSourceSyncRunRepository extends JpaRepository<JobSourceSyncRun, Long> {
    Optional<JobSourceSyncRun> findByJobSourceAndStatus(JobSource jobSource, JobSourceSyncStatus status);
    List<JobSourceSyncRun> findAllByStatus(JobSourceSyncStatus status);
}
