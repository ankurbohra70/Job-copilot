package com.jobcopilot.matching;

import com.jobcopilot.matching.dto.MatchRequest;
import com.jobcopilot.matching.dto.MatchResponse;
import org.springframework.stereotype.Service;

@Service
public class MatchService {
    private final JobAssessmentService assessments;

    public MatchService(JobAssessmentService assessments) {
        this.assessments = assessments;
    }

    public MatchResponse match(MatchRequest request) {
        return assessments.assess(request.jobId(), request.candidateProfileId());
    }
}
