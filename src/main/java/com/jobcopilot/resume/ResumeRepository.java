package com.jobcopilot.resume;
import org.springframework.data.jpa.repository.JpaRepository;
interface ResumeRepository extends JpaRepository<Resume, Long> {}

