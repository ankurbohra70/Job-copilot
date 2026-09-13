package com.jobcopilot.discovery;

import com.jobcopilot.discovery.lever.LeverRegion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;

interface JobSourceRepository extends JpaRepository<JobSource, Long> {
    Optional<JobSource> findByProviderAndRegionAndSourceKey(
            JobSourceProvider provider, LeverRegion region, String sourceKey);

    List<JobSource> findByEnabledTrue();

    Page<JobSource> findAllByEnabled(boolean enabled, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select source from JobSource source where source.id = :sourceId")
    Optional<JobSource> findLockedById(@Param("sourceId") long sourceId);

    @Query(value = """
            select source.id as sourceId,
                   case
                     when running.id is not null then running.lease_expires_at
                     when terminal.id is null then source.created_at
                     else terminal.completed_at + (:cadenceMillis * interval '1 millisecond')
                   end as dueAt
            from job_sources source
            left join lateral (
                select run.id, run.lease_expires_at
                from job_source_sync_runs run
                where run.job_source_id = source.id and run.status = 'RUNNING'
                limit 1
            ) running on true
            left join lateral (
                select run.id, run.completed_at
                from job_source_sync_runs run
                where run.job_source_id = source.id and run.status <> 'RUNNING'
                order by run.completed_at desc, run.id desc
                limit 1
            ) terminal on true
            where source.enabled = true
              and (
                (running.id is not null and running.lease_expires_at <= clock_timestamp())
                or
                (running.id is null and (
                    terminal.id is null
                    or terminal.completed_at + (:cadenceMillis * interval '1 millisecond') <= clock_timestamp()
                ))
              )
            order by dueAt asc, source.id asc
            limit :candidateLimit
            """, nativeQuery = true)
    List<ScheduledSyncCandidate> findScheduledCandidates(
            @Param("cadenceMillis") long cadenceMillis,
            @Param("candidateLimit") int candidateLimit);
}
