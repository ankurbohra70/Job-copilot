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

import java.util.Locale;
import java.util.Set;

@Service
public class JobService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final String DEFAULT_SORT = "createdAt,desc";
    private static final char LIKE_ESCAPE_CHARACTER = '\\';
    private static final Set<String> SORTABLE_FIELDS = Set.of(
            "id", "title", "company", "status", "createdAt", "updatedAt"
    );

    private final JobRepository jobRepository;

    JobService(JobRepository jobRepository) {
        this.jobRepository = jobRepository;
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
