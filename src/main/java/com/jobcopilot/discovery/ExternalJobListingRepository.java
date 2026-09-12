package com.jobcopilot.discovery;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

interface ExternalJobListingRepository extends JpaRepository<ExternalJobListing, Long> {
    Optional<ExternalJobListing> findByJobSourceAndExternalJobId(JobSource jobSource, String externalJobId);
    Optional<ExternalJobListing> findByJobId(Long jobId);
    List<ExternalJobListing> findByJobSource(JobSource jobSource);

    @Query(value = """
            select new com.jobcopilot.discovery.ExternalJobListingReadProjection(
                listing.id, source.id, listing.externalJobId, listing.availability,
                job.id, job.status, job.description, job.minYearsExperience,
                listing.hostedJobUrl, listing.applyUrl, listing.extractionFingerprint,
                listing.firstSeenAt, listing.lastSeenAt, listing.lastVerifiedAt)
            from ExternalJobListing listing
            join listing.jobSource source
            join listing.job job
            where source.id = :sourceId
              and (:availability is null or listing.availability = :availability)
              and (:jobId is null or job.id = :jobId)
            """, countQuery = """
            select count(listing.id)
            from ExternalJobListing listing
            join listing.jobSource source
            join listing.job job
            where source.id = :sourceId
              and (:availability is null or listing.availability = :availability)
              and (:jobId is null or job.id = :jobId)
            """)
    Page<ExternalJobListingReadProjection> findPage(
            @Param("sourceId") long sourceId,
            @Param("availability") ListingAvailability availability,
            @Param("jobId") Long jobId,
            Pageable pageable);

    @Query("""
            select new com.jobcopilot.discovery.ExternalJobListingReadProjection(
                listing.id, source.id, listing.externalJobId, listing.availability,
                job.id, job.status, job.description, job.minYearsExperience,
                listing.hostedJobUrl, listing.applyUrl, listing.extractionFingerprint,
                listing.firstSeenAt, listing.lastSeenAt, listing.lastVerifiedAt)
            from ExternalJobListing listing
            join listing.jobSource source
            join listing.job job
            where source.id = :sourceId and listing.id = :listingId
            """)
    Optional<ExternalJobListingReadProjection> findReadProjection(
            @Param("sourceId") long sourceId, @Param("listingId") long listingId);
}
