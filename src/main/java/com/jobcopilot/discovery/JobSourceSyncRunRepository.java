package com.jobcopilot.discovery;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Optional;
import java.util.List;

interface JobSourceSyncRunRepository extends JpaRepository<JobSourceSyncRun, Long> {
    Optional<JobSourceSyncRun> findByJobSourceAndStatus(JobSource jobSource, JobSourceSyncStatus status);
    List<JobSourceSyncRun> findAllByStatus(JobSourceSyncStatus status);
    Page<JobSourceSyncRun> findAllByJobSourceId(long sourceId, Pageable pageable);
    Page<JobSourceSyncRun> findAllByJobSourceIdAndStatus(
            long sourceId, JobSourceSyncStatus status, Pageable pageable);
    Optional<JobSourceSyncRun> findByIdAndJobSourceId(long id, long sourceId);
}
