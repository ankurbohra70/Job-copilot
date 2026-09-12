package com.jobcopilot.discovery;

import com.jobcopilot.discovery.lever.LeverRegion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

interface JobSourceRepository extends JpaRepository<JobSource, Long> {
    Optional<JobSource> findByProviderAndRegionAndSourceKey(
            JobSourceProvider provider, LeverRegion region, String sourceKey);

    List<JobSource> findByEnabledTrue();
}
