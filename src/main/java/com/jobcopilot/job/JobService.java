package com.jobcopilot.job;

import com.jobcopilot.job.dto.CreateJobRequest;
import com.jobcopilot.job.dto.JobPageResponse;
import com.jobcopilot.job.dto.JobResponse;
import com.jobcopilot.job.dto.UpdateJobRequest;
import com.jobcopilot.job.dto.UpdateJobStatusRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Set;
import java.util.List;
import com.jobcopilot.common.text.MatchingVocabulary;
import com.jobcopilot.job.dto.JobRequirementsRequest;
import com.jobcopilot.job.dto.JobRequirementsResponse;

@Service
public class JobService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final String DEFAULT_SORT = "createdAt,desc";
    private static final char LIKE_ESCAPE_CHARACTER = '\\';
    private static final Set<String> SORTABLE_FIELDS = Set.of(
            "id", "title", "company", "status", "createdAt", "updatedAt"
    );

    private final JobRepository jobRepository;
    private final JobRequirementExtractor extractor;

    @Transactional(readOnly = true)
    public JobRequirementsResponse getRequirements(Long id) { return findJob(id).requirements(); }

    @Transactional
    public JobRequirementsResponse replaceRequirements(Long id, JobRequirementsRequest request) {
        Job job = findJob(id);
        List<String> required = normalizedSkills(request.requiredSkills());
        List<String> preferred = normalizedSkills(request.preferredSkills()).stream().filter(s -> !required.contains(s)).toList();
        job.replaceRequirements(required, preferred, request.minYearsExperience());
        return jobRepository.saveAndFlush(job).requirements();
    }

    @Transactional
    public JobRequirementsResponse extractRequirements(Long id) {
        Job job = findJob(id);
        String description = job.getDescription();
        if (description == null || description.isBlank()) {
            throw new JobRequirementExtractionException("Job description must be non-blank to extract requirements");
        }
        JobRequirementExtractor.Extraction extracted = extractor.extract(description);
        List<String> required = normalizedSkills(extracted.requiredSkills());
        List<String> preferred = normalizedSkills(extracted.preferredSkills()).stream()
                .filter(skill -> !required.contains(skill))
                .toList();
        BigDecimal minimum = extracted.minYearsExperience();
        try {
            Job.validateExperience(minimum);
        } catch (IllegalArgumentException invalid) {
            throw new JobRequirementExtractionException(invalid.getMessage());
        }
        JobRequirementsResponse current = job.requirements();
        if (semanticallySame(current, required, preferred, minimum)) {
            return current;
        }
        job.replaceRequirements(required, preferred, minimum);
        return jobRepository.saveAndFlush(job).requirements();
    }

    @Transactional(readOnly = true)
    public JobMatchingSnapshot matchingSnapshot(Long id) {
        return toMatchingSnapshot(findJob(id));
    }

    @Transactional(readOnly = true)
    public JobApplicationSnapshot applicationSnapshot(Long id) {
        Job job = findJob(id);
        return new JobApplicationSnapshot(toMatchingSnapshot(job), job.getCompany(), job.getJobUrl(), job.getStatus());
    }

    @Transactional(readOnly = true)
    public List<JobRankingSnapshot> rankingSnapshots(Set<JobStatus> statuses) {
        return jobRepository.findAllWithSkillsForRanking(statuses).stream()
                .map(JobService::toRankingSnapshot)
                .toList();
    }

    private static List<String> normalizedSkills(List<String> skills) {
        if (skills == null) return List.of();
        return skills.stream().map(MatchingVocabulary.standard()::canonical).peek(skill -> {
            if (skill.isBlank() || skill.length() > 100 || skill.chars().anyMatch(Character::isISOControl))
                throw new InvalidJobRequirementsException();
        }).distinct().sorted().toList();
    }

    private static boolean semanticallySame(
            JobRequirementsResponse current,
            List<String> required,
            List<String> preferred,
            BigDecimal minimum
    ) {
        return current.requiredSkills().equals(required)
                && current.preferredSkills().equals(preferred)
                && sameMinimum(current.minYearsExperience(), minimum);
    }

    private static boolean sameMinimum(BigDecimal left, BigDecimal right) {
        if (left == null || right == null) {
            return left == right;
        }
        return left.compareTo(right) == 0;
    }

    JobService(JobRepository jobRepository, JobRequirementExtractor extractor) {
        this.jobRepository = jobRepository;
        this.extractor = extractor;
    }

    @Transactional
    public JobResponse createJob(CreateJobRequest request) {
        Job job = new Job(
                request.title(),
                request.company(),
                request.location(),
                request.jobUrl(),
                request.description(),
                request.source(),
                request.externalJobId()
        );

        return toResponse(jobRepository.save(job));
    }

    @Transactional(readOnly = true)
    public JobResponse getJob(Long id) {
        return toResponse(findJob(id));
    }

    @Transactional(readOnly = true)
    public JobPageResponse getJobs(
            int page,
            int size,
            String sortExpression,
            String searchTerm,
            JobStatus status
    ) {
        Pageable pageable = PageRequest.of(validatePage(page), validatePageSize(size), parseSort(sortExpression));
        Specification<Job> specification = baseSpecification();

        String normalizedSearch = normalizeSearchTerm(searchTerm);
        if (normalizedSearch != null) {
            specification = specification.and(matchesTitleOrCompany(normalizedSearch));
        }
        if (status != null) {
            specification = specification.and(hasStatus(status));
        }

        Page<JobResponse> jobs = jobRepository.findAll(specification, pageable).map(JobService::toResponse);
        return new JobPageResponse(
                jobs.getContent(),
                jobs.getNumber(),
                jobs.getSize(),
                jobs.getTotalElements(),
                jobs.getTotalPages(),
                jobs.isFirst(),
                jobs.isLast()
        );
    }

    @Transactional
    public JobResponse updateJob(Long id, UpdateJobRequest request) {
        Job job = findJob(id);
        job.replaceDetails(
                request.title(),
                request.company(),
                request.location(),
                request.jobUrl(),
                request.description(),
                request.source(),
                request.externalJobId()
        );
        return toResponse(jobRepository.saveAndFlush(job));
    }

    @Transactional
    public JobResponse updateJobStatus(Long id, UpdateJobStatusRequest request) {
        Job job = findJob(id);
        job.changeStatus(request.status());
        return toResponse(jobRepository.saveAndFlush(job));
    }

    @Transactional
    public void deleteJob(Long id) {
        jobRepository.delete(findJob(id));
    }

    private Job findJob(Long id) {
        return jobRepository.findById(id)
                .orElseThrow(() -> new JobNotFoundException(id));
    }

    private static int validatePageSize(int size) {
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new InvalidJobQueryException("size must be between 1 and " + MAX_PAGE_SIZE);
        }
        return size;
    }

    private static int validatePage(int page) {
        if (page < 0) {
            throw new InvalidJobQueryException("page must be at least 0");
        }
        return page;
    }

    private static Sort parseSort(String sortExpression) {
        String resolvedSort = sortExpression == null ? DEFAULT_SORT : sortExpression;
        String[] parts = resolvedSort.split(",", -1);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new InvalidJobQueryException("sort must use the format field,direction");
        }

        String field = parts[0].trim();
        String direction = parts[1].trim().toLowerCase(Locale.ROOT);
        if (!SORTABLE_FIELDS.contains(field)) {
            throw new InvalidJobQueryException("unsupported sort field: " + field);
        }
        if (!direction.equals("asc") && !direction.equals("desc")) {
            throw new InvalidJobQueryException("sort direction must be asc or desc");
        }

        Sort.Direction sortDirection = Sort.Direction.fromString(direction);
        Sort sort = Sort.by(sortDirection, field);
        return field.equals("id") ? sort : sort.and(Sort.by(sortDirection, "id"));
    }

    private static Specification<Job> baseSpecification() {
        return (root, query, criteriaBuilder) -> criteriaBuilder.conjunction();
    }

    private static Specification<Job> matchesTitleOrCompany(String searchTerm) {
        String pattern = "%" + escapeLikePattern(searchTerm.toLowerCase(Locale.ROOT)) + "%";
        return (root, query, criteriaBuilder) -> criteriaBuilder.or(
                criteriaBuilder.like(criteriaBuilder.lower(root.get("title")), pattern, LIKE_ESCAPE_CHARACTER),
                criteriaBuilder.like(criteriaBuilder.lower(root.get("company")), pattern, LIKE_ESCAPE_CHARACTER)
        );
    }

    private static String escapeLikePattern(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    private static Specification<Job> hasStatus(JobStatus status) {
        return (root, query, criteriaBuilder) -> criteriaBuilder.equal(root.get("status"), status);
    }

    private static String normalizeSearchTerm(String searchTerm) {
        if (searchTerm == null || searchTerm.isBlank()) {
            return null;
        }
        return searchTerm.trim();
    }

    private static JobMatchingSnapshot toMatchingSnapshot(Job job) {
        return new JobMatchingSnapshot(
                job.getId(),
                job.getTitle(),
                job.getDescription(),
                job.getLocation(),
                job.requirements(),
                job.getUpdatedAt()
        );
    }

    private static JobRankingSnapshot toRankingSnapshot(Job job) {
        return new JobRankingSnapshot(
                toMatchingSnapshot(job),
                job.getCompany(),
                job.getJobUrl(),
                job.getStatus(),
                job.getCreatedAt()
        );
    }

    private static JobResponse toResponse(Job job) {
        return new JobResponse(
                job.getId(),
                job.getTitle(),
                job.getCompany(),
                job.getLocation(),
                job.getJobUrl(),
                job.getDescription(),
                job.getSource(),
                job.getExternalJobId(),
                job.getStatus(),
                job.getCreatedAt(),
                job.getUpdatedAt()
        );
    }
}
