package com.jobcopilot.job;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Set;

interface JobRepository extends JpaRepository<Job, Long>, JpaSpecificationExecutor<Job> {
    @Query("""
            select distinct job
            from Job job
            left join fetch job.skills
            where job.status in :statuses
            """)
    List<Job> findAllWithSkillsForRanking(@Param("statuses") Collection<JobStatus> statuses);

    @Query("""
            select distinct job
            from Job job
            left join fetch job.skills
            where job.id in :jobIds
            """)
    List<Job> findAllWithSkillsForRankingByIdIn(@Param("jobIds") Collection<Long> jobIds);

    @Query("select distinct job.id from Job job join job.skills where job.id in :jobIds")
    Set<Long> findIdsWithSkills(@Param("jobIds") Collection<Long> jobIds);
}
