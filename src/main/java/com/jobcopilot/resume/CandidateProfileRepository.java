package com.jobcopilot.resume;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
interface CandidateProfileRepository extends JpaRepository<CandidateProfile, Long> {
    Optional<CandidateProfile> findByResumeId(Long resumeId);
}

