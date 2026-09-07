package com.jobcopilot.matching;

import com.jobcopilot.common.text.MatchingVocabulary;
import com.jobcopilot.job.JobService;
import com.jobcopilot.resume.ResumePersistenceService;
import com.jobcopilot.matching.dto.*;
import org.springframework.stereotype.Service;

@Service
public class MatchService {
    private final JobService jobs;
    private final ResumePersistenceService resumes;
    private final DeterministicMatchingEngine engine;
    public MatchService(JobService jobs, ResumePersistenceService resumes, DeterministicMatchingEngine engine) {
        this.jobs = jobs; this.resumes = resumes; this.engine = engine;
    }
    public MatchResponse match(MatchRequest request) {
        var job = jobs.matchingSnapshot(request.jobId());
        var profile = resumes.matchingSnapshot(request.candidateProfileId());
        var result = engine.match(job, profile);
        return MatchResponse.from(profile.id(), job.id(), engine.version(), MatchingVocabulary.standard().version(),
                profile.parserVersion(), profile.assessedOn(), job.updatedAt(), result);
    }
}

