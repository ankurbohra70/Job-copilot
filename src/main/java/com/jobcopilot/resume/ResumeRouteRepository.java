package com.jobcopilot.resume;

import com.jobcopilot.application.ResumeStrategy;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

interface ResumeRouteRepository extends JpaRepository<ResumeRoute, Long> {
    List<ResumeRoute> findByCandidateProfileIdOrderById(Long candidateProfileId);
    Optional<ResumeRoute> findFirstByCandidateProfileIdAndStrategyAndApprovedTrueAndIsDefaultTrue(
            Long candidateProfileId, ResumeStrategy strategy);
    Optional<ResumeRoute> findFirstByCandidateProfileIdAndStrategyAndApprovedTrueAndRoleFamilyIgnoreCase(
            Long candidateProfileId, ResumeStrategy strategy, String roleFamily);
}
