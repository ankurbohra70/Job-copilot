package com.jobcopilot.discovery;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import java.util.Optional;
import java.util.List;

interface JobSourceSyncRunRepository extends JpaRepository<JobSourceSyncRun, Long> {
    Optional<JobSourceSyncRun> findByJobSourceAndStatus(JobSource jobSource, JobSourceSyncStatus status);
    List<JobSourceSyncRun> findAllByStatus(JobSourceSyncStatus status);
    Page<JobSourceSyncRun> findAllByJobSourceId(long sourceId, Pageable pageable);
    Page<JobSourceSyncRun> findAllByJobSourceIdAndStatus(
            long sourceId, JobSourceSyncStatus status, Pageable pageable);
    Optional<JobSourceSyncRun> findByIdAndJobSourceId(long id, long sourceId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select run from JobSourceSyncRun run where run.id = :runId")
    Optional<JobSourceSyncRun> findLockedById(@Param("runId") long runId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select run from JobSourceSyncRun run
            where run.jobSource.id = :sourceId and run.status = com.jobcopilot.discovery.JobSourceSyncStatus.RUNNING
            """)
    Optional<JobSourceSyncRun> findRunningForUpdate(@Param("sourceId") long sourceId);

    @Query("""
            select run from JobSourceSyncRun run
            where run.jobSource.id = :sourceId and run.status <> com.jobcopilot.discovery.JobSourceSyncStatus.RUNNING
            order by run.completedAt desc, run.id desc
            """)
    List<JobSourceSyncRun> findTerminalHistory(@Param("sourceId") long sourceId, Pageable pageable);
}
