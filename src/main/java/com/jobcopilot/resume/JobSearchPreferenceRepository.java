package com.jobcopilot.resume;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

interface JobSearchPreferenceRepository extends JpaRepository<JobSearchPreference, Long> {
    Optional<JobSearchPreference> findByCandidateProfileId(Long candidateProfileId);
}
