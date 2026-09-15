package com.jobcopilot.job;

import com.jobcopilot.job.dto.CreateJobRequest;
import com.jobcopilot.job.dto.JobPageResponse;
import com.jobcopilot.job.dto.JobResponse;
import com.jobcopilot.job.dto.UpdateJobRequest;
import com.jobcopilot.job.dto.UpdateJobStatusRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class JobServiceTest {
    @Test
    void rejectsSkillsThatExceedStorageLengthAfterUnicodeNormalization() {
        when(jobRepository.findById(7L)).thenReturn(Optional.of(persistedJob(7L, JobStatus.DISCOVERED)));
        assertThrows(InvalidJobRequirementsException.class, () -> jobService.replaceRequirements(7L,
                new com.jobcopilot.job.dto.JobRequirementsRequest(List.of("\uFB03".repeat(100)), null, null)));
        verify(jobRepository, never()).saveAndFlush(any());
    }
    @Test
    void requirementsNormalizeAliasesAndRequiredWinsWhileLegacyUpdatesPreserveThem() {
        Job existing = persistedJob(7L, JobStatus.APPLIED);
        when(jobRepository.findById(7L)).thenReturn(Optional.of(existing));
        when(jobRepository.saveAndFlush(any(Job.class))).thenAnswer(i -> i.getArgument(0));
        var response = jobService.replaceRequirements(7L, new com.jobcopilot.job.dto.JobRequirementsRequest(
                List.of(" Java ", "Postgres", "postgresql"), List.of("postgres", "Docker"), new java.math.BigDecimal("3")));
        assertEquals(List.of("java", "postgresql"), response.requiredSkills());
        assertEquals(List.of("docker"), response.preferredSkills());
        jobService.updateJob(7L, updateRequest());
        assertEquals(response, jobService.getRequirements(7L));
        assertEquals(JobStatus.APPLIED, existing.getStatus());
    }

    @Test
    void requirementsCanBeClearedAndMissingJobFails() {
        Job existing = persistedJob(7L, JobStatus.DISCOVERED);
        when(jobRepository.findById(7L)).thenReturn(Optional.of(existing));
        when(jobRepository.saveAndFlush(any(Job.class))).thenAnswer(i -> i.getArgument(0));
        var response = jobService.replaceRequirements(7L, new com.jobcopilot.job.dto.JobRequirementsRequest(null,null,null));
        assertTrue(response.requiredSkills().isEmpty());
        assertThrows(JobNotFoundException.class, () -> jobService.getRequirements(99L));
    }

    private JobRepository jobRepository;
    private JobService jobService;

    @BeforeEach
    void setUp() {
        jobRepository = mock(JobRepository.class);
        jobService = new JobService(jobRepository, new JobRequirementExtractor());
    }

    @Test
    void createJobStartsAsDiscovered() {
        when(jobRepository.save(any(Job.class))).thenAnswer(invocation -> invocation.getArgument(0));

        JobResponse response = jobService.createJob(createRequest());

        ArgumentCaptor<Job> captor = ArgumentCaptor.forClass(Job.class);
        verify(jobRepository).save(captor.capture());
        assertEquals(JobStatus.DISCOVERED, captor.getValue().getStatus());
        assertEquals(JobStatus.DISCOVERED, response.status());
    }

    @Test
    void getExistingJobMapsAllCoreFields() {
        Job job = persistedJob(7L, JobStatus.SHORTLISTED);
        when(jobRepository.findById(7L)).thenReturn(Optional.of(job));

        JobResponse response = jobService.getJob(7L);

        assertEquals(7L, response.id());
        assertEquals("Backend Software Engineer", response.title());
        assertEquals("Example Technologies", response.company());
        assertEquals(JobStatus.SHORTLISTED, response.status());
        assertEquals(job.getCreatedAt(), response.createdAt());
    }

    @Test
    void getMissingJobThrowsNotFound() {
        when(jobRepository.findById(99L)).thenReturn(Optional.empty());

        JobNotFoundException exception = assertThrows(JobNotFoundException.class, () -> jobService.getJob(99L));

        assertEquals("Job 99 was not found", exception.getMessage());
    }

    @Test
    void rankingSnapshotIdReadIsEmptySafeAndDoesNotLoadUnrelatedStatuses() {
        Job included = persistedJob(7L, JobStatus.DISCOVERED);
        when(jobRepository.findAllWithSkillsForRankingByIdIn(Set.of(7L))).thenReturn(List.of(included));

        assertEquals(List.of(7L), jobService.rankingSnapshotsByIds(Set.of(7L)).stream()
                .map(value -> value.matching().id()).toList());
        verify(jobRepository).findAllWithSkillsForRankingByIdIn(Set.of(7L));
        verify(jobRepository, never()).findAllWithSkillsForRanking(any());

        assertTrue(jobService.rankingSnapshotsByIds(Set.of()).isEmpty());
    }

    @Test
    void updateMutatesExistingJobAndPreservesIdentityStatusAndCreatedAt() {
        Job existing = persistedJob(7L, JobStatus.APPLIED);
        LocalDateTime createdAt = existing.getCreatedAt();
        when(jobRepository.findById(7L)).thenReturn(Optional.of(existing));
        when(jobRepository.saveAndFlush(any(Job.class))).thenAnswer(invocation -> invocation.getArgument(0));

        JobResponse response = jobService.updateJob(7L, updateRequest());

        ArgumentCaptor<Job> captor = ArgumentCaptor.forClass(Job.class);
        verify(jobRepository).saveAndFlush(captor.capture());
        assertSame(existing, captor.getValue());
        assertEquals(7L, response.id());
        assertEquals(JobStatus.APPLIED, response.status());
        assertEquals(createdAt, response.createdAt());
        assertEquals("Senior Backend Engineer", response.title());
        assertEquals("Updated Company", response.company());
    }

    @Test
    void updateMissingJobThrowsNotFoundAndDoesNotSave() {
        when(jobRepository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(JobNotFoundException.class, () -> jobService.updateJob(99L, updateRequest()));

        verify(jobRepository, never()).saveAndFlush(any());
    }

    @Test
    void statusUpdateChangesOnlyStatusDomainState() {
        Job existing = persistedJob(7L, JobStatus.DISCOVERED);
        String originalTitle = existing.getTitle();
        String originalCompany = existing.getCompany();
        LocalDateTime originalCreatedAt = existing.getCreatedAt();
        when(jobRepository.findById(7L)).thenReturn(Optional.of(existing));
        when(jobRepository.saveAndFlush(any(Job.class))).thenAnswer(invocation -> invocation.getArgument(0));

        JobResponse response = jobService.updateJobStatus(
                7L,
                new UpdateJobStatusRequest(JobStatus.INTERVIEWING)
        );

        assertEquals(JobStatus.INTERVIEWING, response.status());
        assertEquals(originalTitle, response.title());
        assertEquals(originalCompany, response.company());
        assertEquals(originalCreatedAt, response.createdAt());
        verify(jobRepository).saveAndFlush(existing);
    }

    @Test
    void statusUpdateForMissingJobThrowsNotFound() {
        when(jobRepository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(JobNotFoundException.class, () -> jobService.updateJobStatus(
                99L,
                new UpdateJobStatusRequest(JobStatus.APPLIED)
        ));
        verify(jobRepository, never()).saveAndFlush(any());
    }

    @Test
    void deleteExistingJobUsesLoadedEntity() {
        Job existing = persistedJob(7L, JobStatus.DISCOVERED);
        when(jobRepository.findById(7L)).thenReturn(Optional.of(existing));

        jobService.deleteJob(7L);

        verify(jobRepository).delete(existing);
    }

    @Test
    void deleteMissingJobThrowsNotFoundAndDoesNotDelete() {
        when(jobRepository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(JobNotFoundException.class, () -> jobService.deleteJob(99L));

        verify(jobRepository, never()).delete(any(Job.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void listUsesSpecificationPageableAndMapsPageMetadata() {
        Job job = persistedJob(7L, JobStatus.SHORTLISTED);
        when(jobRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenAnswer(invocation -> {
                    Pageable pageable = invocation.getArgument(1);
                    return new PageImpl<>(List.of(job), pageable, 21);
                });

        JobPageResponse response = jobService.getJobs(
                1,
                10,
                "company,asc",
                " backend ",
                JobStatus.SHORTLISTED
        );

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(jobRepository).findAll(any(Specification.class), pageableCaptor.capture());
        Pageable pageable = pageableCaptor.getValue();
        assertEquals(1, pageable.getPageNumber());
        assertEquals(10, pageable.getPageSize());
        assertEquals("ASC", pageable.getSort().getOrderFor("company").getDirection().name());
        assertEquals(
                List.of(
                        new Sort.Order(Sort.Direction.ASC, "company"),
                        new Sort.Order(Sort.Direction.ASC, "id")
                ),
                pageable.getSort().toList()
        );
        assertEquals(1, response.page());
        assertEquals(10, response.size());
        assertEquals(21, response.totalElements());
        assertEquals(3, response.totalPages());
        assertFalse(response.first());
        assertFalse(response.last());
        assertEquals(1, response.content().size());
        assertEquals(7L, response.content().getFirst().id());
        assertEquals(JobStatus.SHORTLISTED, response.content().getFirst().status());
    }

    @Test
    @SuppressWarnings("unchecked")
    void blankSearchStillUsesAValidSpecification() {
        when(jobRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        JobPageResponse response = jobService.getJobs(0, 20, "createdAt,desc", "   ", null);

        verify(jobRepository).findAll(any(Specification.class), any(Pageable.class));
        assertTrue(response.content().isEmpty());
    }

    @Test
    void listRejectsInvalidPagination() {
        assertThrows(InvalidJobQueryException.class,
                () -> jobService.getJobs(-1, 20, "createdAt,desc", null, null));
        assertThrows(InvalidJobQueryException.class,
                () -> jobService.getJobs(0, 0, "createdAt,desc", null, null));
        assertThrows(InvalidJobQueryException.class,
                () -> jobService.getJobs(0, 101, "createdAt,desc", null, null));
    }

    @Test
    void listRejectsInvalidSortExpressions() {
        assertThrows(InvalidJobQueryException.class,
                () -> jobService.getJobs(0, 20, "description,asc", null, null));
        assertThrows(InvalidJobQueryException.class,
                () -> jobService.getJobs(0, 20, "title,sideways", null, null));
        assertThrows(InvalidJobQueryException.class,
                () -> jobService.getJobs(0, 20, "title", null, null));
        assertThrows(InvalidJobQueryException.class,
                () -> jobService.getJobs(0, 20, "title,asc,extra", null, null));
        assertThrows(InvalidJobQueryException.class,
                () -> jobService.getJobs(0, 20, "", null, null));
        assertThrows(InvalidJobQueryException.class,
                () -> jobService.getJobs(0, 20, "   ", null, null));
    }

    @Test
    @SuppressWarnings("unchecked")
    void omittedSortUsesDefaultWithMatchingIdTieBreaker() {
        when(jobRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        jobService.getJobs(0, 20, null, null, null);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(jobRepository).findAll(any(Specification.class), captor.capture());
        assertEquals(
                List.of(
                        new Sort.Order(Sort.Direction.DESC, "createdAt"),
                        new Sort.Order(Sort.Direction.DESC, "id")
                ),
                captor.getValue().getSort().toList()
        );
    }

    @Test
    @SuppressWarnings("unchecked")
    void idPrimarySortIsNotDuplicatedAndDirectionIsCaseInsensitive() {
        when(jobRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        jobService.getJobs(0, 20, "id,DESC", null, null);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(jobRepository).findAll(any(Specification.class), captor.capture());
        assertEquals(
                List.of(new Sort.Order(Sort.Direction.DESC, "id")),
                captor.getValue().getSort().toList()
        );
    }

    @Test
    @SuppressWarnings("unchecked")
    void rankingSnapshotsUseTheBulkPathAndEmptyDatabaseIsEmpty() {
        when(jobRepository.findAllWithSkillsForRanking(any())).thenReturn(List.of());

        assertTrue(jobService.rankingSnapshots(Set.of(JobStatus.DISCOVERED, JobStatus.SHORTLISTED)).isEmpty());

        verify(jobRepository).findAllWithSkillsForRanking(Set.of(JobStatus.DISCOVERED, JobStatus.SHORTLISTED));
        verify(jobRepository, never()).findById(any());
        verify(jobRepository, never()).findAll(any(Specification.class), any(Pageable.class));
    }

    @Test
    void rankingSnapshotsCopyPresentationMetadataAndMatchingRequirements() {
        Job rejected = persistedJob(8L, JobStatus.REJECTED);
        rejected.replaceRequirements(List.of("java"), List.of("docker"), new java.math.BigDecimal("3"));
        when(jobRepository.findAllWithSkillsForRanking(any())).thenReturn(List.of(rejected));
        when(jobRepository.findById(8L)).thenReturn(Optional.of(rejected));

        var snapshots = jobService.rankingSnapshots(Set.of(JobStatus.REJECTED));
        var snapshot = snapshots.getFirst();
        var matching = jobService.matchingSnapshot(8L);

        assertEquals(1, snapshots.size());
        assertEquals(8L, snapshot.matching().id());
        assertEquals("Backend Software Engineer", snapshot.matching().title());
        assertEquals("Example Technologies", snapshot.company());
        assertEquals("Gurugram", snapshot.matching().location());
        assertEquals("https://example.com/jobs/123", snapshot.jobUrl());
        assertEquals(JobStatus.REJECTED, snapshot.status());
        assertEquals(rejected.getCreatedAt(), snapshot.createdAt());
        assertEquals(rejected.getUpdatedAt(), snapshot.matching().updatedAt());
        assertEquals(matching, snapshot.matching());
        assertEquals(List.of("java"), snapshot.matching().requirements().requiredSkills());
        assertEquals(List.of("docker"), snapshot.matching().requirements().preferredSkills());
        verify(jobRepository).findAllWithSkillsForRanking(Set.of(JobStatus.REJECTED));
    }

    @Test
    void extractRequirementsDoesNotInvokeExtractorWhenJobIsMissing() {
        JobRequirementExtractor extractor = mock(JobRequirementExtractor.class);
        JobService service = new JobService(jobRepository, extractor);
        when(jobRepository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(JobNotFoundException.class, () -> service.extractRequirements(99L));

        verifyNoInteractions(extractor);
        verify(jobRepository, never()).saveAndFlush(any());
    }

    @Test
    void extractRequirementsRejectsBlankAndNullDescriptionsWithoutMutating() {
        Job existing = persistedJob(7L, JobStatus.DISCOVERED);
        existing.replaceRequirements(List.of("java"), List.of("redis"), new BigDecimal("2"));
        when(jobRepository.findById(7L)).thenReturn(Optional.of(existing));

        ReflectionTestUtils.setField(existing, "description", "   ");
        assertEquals(
                "Job description must be non-blank to extract requirements",
                assertThrows(JobRequirementExtractionException.class, () -> jobService.extractRequirements(7L)).getMessage());
        ReflectionTestUtils.setField(existing, "description", null);
        assertThrows(JobRequirementExtractionException.class, () -> jobService.extractRequirements(7L));

        assertEquals(List.of("java"), existing.requirements().requiredSkills());
        assertEquals(List.of("redis"), existing.requirements().preferredSkills());
        assertEquals(0, existing.requirements().minYearsExperience().compareTo(new BigDecimal("2")));
        verify(jobRepository, never()).saveAndFlush(any());
    }

    @Test
    void failedExtractionPreservesExistingRequirements() {
        Job existing = persistedJob(7L, JobStatus.APPLIED);
        existing.replaceRequirements(List.of("java", "spring-boot"), List.of("redis"), new BigDecimal("2"));
        LocalDateTime updatedAt = existing.getUpdatedAt();
        ReflectionTestUtils.setField(existing, "description", "A friendly workplace with no technologies listed.");
        when(jobRepository.findById(7L)).thenReturn(Optional.of(existing));

        assertThrows(JobRequirementExtractionException.class, () -> jobService.extractRequirements(7L));

        assertEquals(List.of("java", "spring-boot"), existing.requirements().requiredSkills());
        assertEquals(List.of("redis"), existing.requirements().preferredSkills());
        assertEquals(0, existing.requirements().minYearsExperience().compareTo(new BigDecimal("2")));
        assertEquals(updatedAt, existing.getUpdatedAt());
        verify(jobRepository, never()).saveAndFlush(any());
    }

    @Test
    void extractRequirementsCanPersistSkillsOnlyAndExperienceOnly() {
        Job existing = persistedJob(7L, JobStatus.DISCOVERED);
        when(jobRepository.findById(7L)).thenReturn(Optional.of(existing));
        when(jobRepository.saveAndFlush(any(Job.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ReflectionTestUtils.setField(existing, "description", "Required: Java and Redis");
        var skillsOnly = jobService.extractRequirements(7L);
        assertEquals(List.of("java", "redis"), skillsOnly.requiredSkills());
        assertTrue(skillsOnly.preferredSkills().isEmpty());
        assertEquals(null, skillsOnly.minYearsExperience());

        ReflectionTestUtils.setField(existing, "description", "Minimum 3 years of experience");
        var experienceOnly = jobService.extractRequirements(7L);
        assertTrue(experienceOnly.requiredSkills().isEmpty());
        assertTrue(experienceOnly.preferredSkills().isEmpty());
        assertEquals(0, experienceOnly.minYearsExperience().compareTo(new BigDecimal("3")));
    }

    @Test
    void successfulExtractionFullyReplacesManualRequirementsAndKeepsListsDisjoint() {
        Job existing = persistedJob(7L, JobStatus.SHORTLISTED);
        existing.replaceRequirements(List.of("java"), List.of("aws"), new BigDecimal("3"));
        ReflectionTestUtils.setField(existing, "description", """
                Requirements:
                Redis
                Preferred:
                Docker
                """);
        when(jobRepository.findById(7L)).thenReturn(Optional.of(existing));
        when(jobRepository.saveAndFlush(any(Job.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = jobService.extractRequirements(7L);

        assertEquals(List.of("redis"), response.requiredSkills());
        assertEquals(List.of("docker"), response.preferredSkills());
        assertEquals(null, response.minYearsExperience());
        verify(jobRepository).saveAndFlush(existing);
    }

    @Test
    void identicalExtractionIsASemanticNoOpEvenWhenBigDecimalScaleDiffers() {
        Job existing = persistedJob(7L, JobStatus.DISCOVERED);
        existing.replaceRequirements(List.of("java"), List.of("docker"), new BigDecimal("2.00"));
        LocalDateTime updatedAt = existing.getUpdatedAt();
        ReflectionTestUtils.setField(existing, "description", """
                Requirements:
                Java
                2 years of experience

                Preferred:
                Docker
                """);
        when(jobRepository.findById(7L)).thenReturn(Optional.of(existing));

        var response = jobService.extractRequirements(7L);

        assertEquals(List.of("java"), response.requiredSkills());
        assertEquals(List.of("docker"), response.preferredSkills());
        assertEquals(0, response.minYearsExperience().compareTo(new BigDecimal("2")));
        assertEquals(updatedAt, existing.getUpdatedAt());
        verify(jobRepository, never()).saveAndFlush(any());
        verify(jobRepository, never()).save(any());
    }

    @Test
    void manualAndExtractedExperienceShareCanonicalScale() {
        Job manual = persistedJob(7L, JobStatus.DISCOVERED);
        when(jobRepository.findById(7L)).thenReturn(Optional.of(manual));
        when(jobRepository.saveAndFlush(any(Job.class))).thenAnswer(invocation -> invocation.getArgument(0));
        var put = jobService.replaceRequirements(7L, new com.jobcopilot.job.dto.JobRequirementsRequest(
                List.of("java"), List.of("docker"), new BigDecimal("2")));
        Job extractedJob = persistedJob(8L, JobStatus.DISCOVERED);
        ReflectionTestUtils.setField(extractedJob, "description", """
                Requirements:
                Java
                2 years of experience

                Preferred:
                Docker
                """);
        when(jobRepository.findById(8L)).thenReturn(Optional.of(extractedJob));
        var extracted = jobService.extractRequirements(8L);
        assertEquals(new BigDecimal("2.00"), put.minYearsExperience());
        assertEquals(put, extracted);
        assertEquals(put.minYearsExperience(), extracted.minYearsExperience());
    }

    private static CreateJobRequest createRequest() {
        return new CreateJobRequest(
                "Backend Software Engineer",
                "Example Technologies",
                "Gurugram",
                "https://example.com/jobs/123",
                "Java Spring Boot backend role",
                "MANUAL",
                "123"
        );
    }

    private static UpdateJobRequest updateRequest() {
        return new UpdateJobRequest(
                "Senior Backend Engineer",
                "Updated Company",
                "Remote",
                "https://example.com/jobs/updated",
                "Updated description",
                "MANUAL",
                "updated-123"
        );
    }

    private static Job persistedJob(Long id, JobStatus status) {
        Job job = new Job(
                "Backend Software Engineer",
                "Example Technologies",
                "Gurugram",
                "https://example.com/jobs/123",
                "Java Spring Boot backend role",
                "MANUAL",
                "123"
        );
        ReflectionTestUtils.setField(job, "id", id);
        ReflectionTestUtils.setField(job, "status", status);
        ReflectionTestUtils.setField(job, "createdAt", LocalDateTime.of(2026, 9, 3, 10, 30));
        ReflectionTestUtils.setField(job, "updatedAt", LocalDateTime.of(2026, 9, 3, 10, 30));
        return job;
    }
}
