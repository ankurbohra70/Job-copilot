package com.jobcopilot.job;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

interface JobRepository extends JpaRepository<Job, Long>, JpaSpecificationExecutor<Job> {
    @Query("""
            select distinct job
            from Job job
            left join fetch job.skills
            where job.status in :statuses
            """)
    List<Job> findAllWithSkillsForRanking(@Param("statuses") Collection<JobStatus> statuses);
}
