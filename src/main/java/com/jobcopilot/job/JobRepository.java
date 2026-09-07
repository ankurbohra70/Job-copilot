package com.jobcopilot.job;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

interface JobRepository extends JpaRepository<Job, Long>, JpaSpecificationExecutor<Job> {
    @Query("""
            select distinct job
            from Job job
            left join fetch job.skills
            """)
    List<Job> findAllWithSkillsForRanking();
}
