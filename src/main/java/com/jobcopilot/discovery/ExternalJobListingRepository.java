package com.jobcopilot.discovery;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

interface ExternalJobListingRepository extends JpaRepository<ExternalJobListing, Long> {
    Optional<ExternalJobListing> findByJobSourceAndExternalJobId(JobSource jobSource, String externalJobId);
    Optional<ExternalJobListing> findByJobId(Long jobId);
    List<ExternalJobListing> findByJobSource(JobSource jobSource);
}
