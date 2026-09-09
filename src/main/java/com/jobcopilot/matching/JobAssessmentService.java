package com.jobcopilot.matching;

import com.jobcopilot.common.text.MatchingVocabulary;
import com.jobcopilot.job.JobService;
import com.jobcopilot.matching.dto.MatchResponse;
import com.jobcopilot.resume.ResumePersistenceService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class JobAssessmentService {
    private final JobService jobs;
    private final ResumePersistenceService resumes;
    private final DeterministicMatchingEngine engine;

    public JobAssessmentService(JobService jobs, ResumePersistenceService resumes, DeterministicMatchingEngine engine) {
        this.jobs = jobs;
        this.resumes = resumes;
        this.engine = engine;
    }

    @Transactional(readOnly = true)
    public MatchResponse assess(Long jobId, Long candidateProfileId) {
        var job = jobs.matchingSnapshot(jobId);
        var profile = resumes.matchingSnapshot(candidateProfileId);
        var result = engine.match(job, profile);
        return MatchResponse.from(
                profile.id(),
                job.id(),
                engine.version(),
                MatchingVocabulary.standard().version(),
                profile.parserVersion(),
                profile.assessedOn(),
                job.updatedAt(),
                result
        );
    }
}
