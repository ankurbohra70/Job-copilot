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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

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

    public JobRankingResponse rank(Long candidateProfileId, JobRankingQuery query) {
        CandidateMatchingSnapshot candidate = resumes.matchingSnapshot(candidateProfileId);
        List<JobRankingSnapshot> snapshots = jobs.rankingSnapshots(query.statuses());

        List<RankedComputation> computable = new ArrayList<>();
        List<UnassessedComputation> unassessed = new ArrayList<>();
        for (JobRankingSnapshot snapshot : snapshots) {
            try {
                computable.add(new RankedComputation(snapshot, engine.match(snapshot.matching(), candidate)));
            } catch (MatchCannotBeComputedException exception) {
                unassessed.add(new UnassessedComputation(snapshot, exception.getMessage()));
            } catch (RuntimeException exception) {
                throw new JobRankingComputationException(snapshot.matching().id(), exception);
            }
        }

        List<RankedComputation> filtered = new ArrayList<>();
        for (RankedComputation computation : computable) {
            if (passesPostMatchFilters(computation.result(), query)) {
                filtered.add(computation);
            }
        }

        filtered.sort(JobRankingService::compareComputable);
        List<RankedJobResponse> rankedJobs = new ArrayList<>(filtered.size());
        for (int index = 0; index < filtered.size(); index++) {
            RankedComputation computation = filtered.get(index);
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
                snapshots.size(),
                computable.size(),
                filtered.size(),
                query.page(),
                query.size(),
                pageSlice(rankedJobs, query.page(), query.size()),
                unassessedJobs
        );
    }

    private static boolean passesPostMatchFilters(MatchResult result, JobRankingQuery query) {
        BigDecimal minScore = query.minScore();
        if (minScore != null && result.overallScore().compareTo(minScore) < 0) {
            return false;
        }
        Set<MatchResult.Recommendation> recommendations = query.recommendations();
        return recommendations.isEmpty() || recommendations.contains(result.recommendation());
    }

    private static List<RankedJobResponse> pageSlice(List<RankedJobResponse> rankedJobs, int page, int size) {
        long from = (long) page * (long) size;
        if (from >= rankedJobs.size()) {
            return List.of();
        }
        int start = (int) from;
        long endExclusive = Math.min(from + size, rankedJobs.size());
        return List.copyOf(rankedJobs.subList(start, (int) endExclusive));
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
