package com.jobcopilot.matching;

import com.jobcopilot.common.text.MatchingVocabulary;
import com.jobcopilot.job.JobRankingSnapshot;
import com.jobcopilot.job.JobService;
import com.jobcopilot.matching.dto.JobRankingResponse;
import com.jobcopilot.matching.dto.RankedJobResponse;
import com.jobcopilot.matching.dto.RankingJobSummary;
import com.jobcopilot.matching.dto.RankingMatchSummary;
import com.jobcopilot.matching.dto.UnassessedJobResponse;
import com.jobcopilot.resume.CandidateMatchingSnapshot;
import com.jobcopilot.resume.ResumePersistenceService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
public class JobRankingService {
    private static final Comparator<JobRankingSnapshot> IDENTITY_ORDER = Comparator
            .comparing(JobRankingSnapshot::createdAt)
            .thenComparing(snapshot -> snapshot.matching().id())
            .reversed();

    private final ResumePersistenceService resumes;
    private final JobService jobs;
    private final DeterministicMatchingEngine engine;

    public JobRankingService(ResumePersistenceService resumes, JobService jobs, DeterministicMatchingEngine engine) {
        this.resumes = resumes;
        this.jobs = jobs;
        this.engine = engine;
    }

    public JobRankingResponse rank(Long candidateProfileId) {
        CandidateMatchingSnapshot candidate = resumes.matchingSnapshot(candidateProfileId);
        List<JobRankingSnapshot> snapshots = jobs.rankingSnapshots();

        List<RankedComputation> ranked = new ArrayList<>();
        List<UnassessedComputation> unassessed = new ArrayList<>();
        for (JobRankingSnapshot snapshot : snapshots) {
            try {
                ranked.add(new RankedComputation(snapshot, engine.match(snapshot.matching(), candidate)));
            } catch (MatchCannotBeComputedException exception) {
                unassessed.add(new UnassessedComputation(snapshot, exception.getMessage()));
            } catch (RuntimeException exception) {
                throw new JobRankingComputationException(snapshot.matching().id(), exception);
            }
        }

        ranked.sort(JobRankingService::compareComputable);
        List<RankedJobResponse> rankedJobs = new ArrayList<>(ranked.size());
        for (int index = 0; index < ranked.size(); index++) {
            RankedComputation computation = ranked.get(index);
            rankedJobs.add(new RankedJobResponse(
                    index + 1,
                    RankingJobSummary.from(computation.snapshot()),
                    RankingMatchSummary.from(computation.result())
            ));
        }

        unassessed.sort(Comparator.comparing(UnassessedComputation::snapshot, IDENTITY_ORDER));
        List<UnassessedJobResponse> unassessedJobs = unassessed.stream()
                .map(item -> new UnassessedJobResponse(
                        RankingJobSummary.from(item.snapshot()),
                        UnassessedJobResponse.Reason.INSUFFICIENT_JOB_REQUIREMENTS,
                        item.message()
                ))
                .toList();

        return JobRankingResponse.of(
                candidate.id(),
                engine.version(),
                MatchingVocabulary.standard().version(),
                candidate.parserVersion(),
                candidate.assessedOn(),
                rankedJobs,
                unassessedJobs
        );
    }

    private static int compareComputable(RankedComputation left, RankedComputation right) {
        int score = right.result().overallScore().compareTo(left.result().overallScore());
        if (score != 0) {
            return score;
        }
        int created = right.snapshot().createdAt().compareTo(left.snapshot().createdAt());
        if (created != 0) {
            return created;
        }
        return right.snapshot().matching().id().compareTo(left.snapshot().matching().id());
    }

    private record RankedComputation(JobRankingSnapshot snapshot, MatchResult result) {
    }

    private record UnassessedComputation(JobRankingSnapshot snapshot, String message) {
    }
}
