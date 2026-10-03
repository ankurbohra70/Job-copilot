package com.jobcopilot.application;

import com.jobcopilot.application.dto.LiveOpportunityPageResponse;
import com.jobcopilot.application.dto.LiveOpportunityResponse;
import com.jobcopilot.application.dto.OpportunityListingSummary;
import com.jobcopilot.common.text.MatchingVocabulary;
import com.jobcopilot.discovery.LiveOpportunityListingSnapshot;
import com.jobcopilot.discovery.LiveOpportunityReadService;
import com.jobcopilot.discovery.ExtractionState;
import com.jobcopilot.discovery.JobVerificationSnapshot;
import com.jobcopilot.discovery.ListingAvailability;
import com.jobcopilot.job.JobApplicationSnapshot;
import com.jobcopilot.job.JobRankingSnapshot;
import com.jobcopilot.job.JobService;
import com.jobcopilot.job.JobStatus;
import com.jobcopilot.matching.DeterministicMatchingEngine;
import com.jobcopilot.matching.JobRankingComputationException;
import com.jobcopilot.matching.JobRankingOrder;
import com.jobcopilot.matching.MatchCannotBeComputedException;
import com.jobcopilot.matching.MatchResult;
import com.jobcopilot.matching.dto.MatchResponse;
import com.jobcopilot.matching.dto.RankingJobSummary;
import com.jobcopilot.matching.dto.UnassessedJobResponse;
import com.jobcopilot.resume.CandidateMatchingSnapshot;
import com.jobcopilot.resume.CandidateProfileFacts;
import com.jobcopilot.resume.JobSearchPreferenceData;
import com.jobcopilot.resume.JobSearchPreferenceService;
import com.jobcopilot.resume.ResumePersistenceService;
import com.jobcopilot.resume.ResumeRouteService;
import com.jobcopilot.resume.ResumeRouteSnapshot;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class LiveOpportunityService {
    private static final Set<JobStatus> PURSUIT_STATUSES = Set.of(JobStatus.DISCOVERED, JobStatus.SHORTLISTED);

    private final ResumePersistenceService profiles;
    private final JobSearchPreferenceService preferences;
    private final LiveOpportunityReadService liveListings;
    private final JobService jobs;
    private final DeterministicMatchingEngine engine;
    private final ApplicationDecisionService decisions;
    private final ApplicationReadinessService readiness;
    private final ResumeRouteService routes;

    public LiveOpportunityService(ResumePersistenceService profiles, JobSearchPreferenceService preferences,
            LiveOpportunityReadService liveListings, JobService jobs, DeterministicMatchingEngine engine,
            ApplicationDecisionService decisions, ApplicationReadinessService readiness,
            ResumeRouteService routes) {
        this.profiles = profiles;
        this.preferences = preferences;
        this.liveListings = liveListings;
        this.jobs = jobs;
        this.engine = engine;
        this.decisions = decisions;
        this.readiness = readiness;
        this.routes = routes;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public LiveOpportunityPageResponse find(Long profileId, OpportunityQuery query) {
        CandidateMatchingSnapshot candidate = profiles.matchingSnapshot(profileId);
        var preference = preferences.find(profileId);
        CandidateProfileFacts facts = profiles.facts(profileId);
        JobSearchPreferenceData preferenceData = preference == null ? null : preference.preference();
        List<ResumeRouteSnapshot> routeCandidates = routes.resolutionCandidates(profileId);
        var listingSet = liveListings.find(PURSUIT_STATUSES);
        Map<Long, LiveOpportunityListingSnapshot> listingsByJob = listingSet.currentListings().stream()
                .collect(Collectors.toUnmodifiableMap(LiveOpportunityListingSnapshot::jobId, Function.identity()));
        List<JobRankingSnapshot> eligibleJobs = jobs.rankingSnapshotsByIds(listingsByJob.keySet());

        List<Computed> computable = new ArrayList<>();
        List<UnassessedJobResponse> unassessed = new ArrayList<>();
        for (JobRankingSnapshot job : eligibleJobs) {
            try {
                MatchResult result = engine.match(job.matching(), candidate);
                MatchResponse match = response(candidate, job, result);
                ApplicationDecisionResult decision = decisions.decide(job.matching().id(), profileId, match,
                        facts, preferenceData, job.matching().title());
                computable.add(new Computed(job, listingsByJob.get(job.matching().id()), result, match, decision));
            } catch (MatchCannotBeComputedException exception) {
                unassessed.add(new UnassessedJobResponse(RankingJobSummary.from(job),
                        UnassessedJobResponse.Reason.INSUFFICIENT_JOB_REQUIREMENTS, exception.getMessage()));
            } catch (RuntimeException exception) {
                throw new JobRankingComputationException(job.matching().id(), exception);
            }
        }

        computable.sort((left, right) -> JobRankingOrder.compare(
                left.job(), left.result(), right.job(), right.result()));
        unassessed.sort(Comparator.<UnassessedJobResponse, java.time.LocalDateTime>comparing(
                        item -> item.job().createdAt(), Comparator.reverseOrder())
                .thenComparing(item -> item.job().id(), Comparator.reverseOrder()));

        List<Ready> filtered = new ArrayList<>();
        for (Computed item : computable) {
            if (!query.ranking().matches(item.result())) continue;
            JobApplicationSnapshot job = new JobApplicationSnapshot(item.job().matching(),
                    item.job().company(), item.job().jobUrl(), item.job().status());
            LiveOpportunityListingSnapshot listing = item.listing();
            JobVerificationSnapshot verification = new JobVerificationSnapshot(listing.listingId(),
                    ListingAvailability.LIVE, listing.hostedJobUrl(), listing.applyUrl(),
                    ExtractionState.CURRENT, listing.lastVerifiedAt());
            ResumeRouteSnapshot route = item.decision().recommendedResumeStrategy() == null ? null
                    : routes.resolve(routeCandidates, item.decision().recommendedResumeStrategy(),
                            item.job().matching().title());
            ApplicationReadinessResult readinessResult = readiness.evaluate(job, profileId, item.match(),
                    item.decision(), facts, preferenceData, verification, route);
            if (query.matches(readinessResult.readiness())) filtered.add(new Ready(item, readinessResult));
        }

        List<LiveOpportunityResponse> ranked = new ArrayList<>(filtered.size());
        for (int index = 0; index < filtered.size(); index++) {
            Ready item = filtered.get(index);
            ranked.add(new LiveOpportunityResponse(index + 1, RankingJobSummary.from(item.computed().job()),
                    OpportunityListingSummary.from(item.computed().listing()), item.computed().match(),
                    item.computed().decision(), item.readiness()));
        }
        List<LiveOpportunityResponse> page = page(ranked, query.ranking().page(), query.ranking().size());
        int totalPages = filtered.isEmpty() ? 0
                : (int) ((filtered.size() + (long) query.ranking().size() - 1) / query.ranking().size());
        int excluded = (int) computable.stream()
                .filter(item -> item.decision().reasons().contains("EXCLUDED_ROLE")).count();
        return new LiveOpportunityPageResponse(candidate.id(), candidate.revision(), engine.version(),
                MatchingVocabulary.standard().version(), candidate.parserVersion(), candidate.assessedOn(),
                preference == null ? null : preference.id(), preference == null ? null : preference.updatedAt(),
                listingSet.liveListingCount(), listingSet.currentListings().size(),
                listingSet.liveListingCount() - listingSet.currentListings().size(), computable.size(),
                unassessed.size(), excluded, filtered.size(), page.size(), query.ranking().page(),
                query.ranking().size(), totalPages, query.ranking().page() == 0,
                (long) query.ranking().page() + 1 >= totalPages, page, unassessed);
    }

    private MatchResponse response(CandidateMatchingSnapshot candidate, JobRankingSnapshot job, MatchResult result) {
        return MatchResponse.from(candidate.id(), job.matching().id(), engine.version(),
                MatchingVocabulary.standard().version(), candidate.parserVersion(), candidate.assessedOn(),
                candidate.revision(), job.matching().updatedAt(), result);
    }

    private static List<LiveOpportunityResponse> page(List<LiveOpportunityResponse> values, int page, int size) {
        long from = (long) page * size;
        if (from >= values.size()) return List.of();
        return List.copyOf(values.subList((int) from, (int) Math.min(from + size, values.size())));
    }

    private record Computed(JobRankingSnapshot job, LiveOpportunityListingSnapshot listing,
            MatchResult result, MatchResponse match, ApplicationDecisionResult decision) {
    }

    private record Ready(Computed computed, ApplicationReadinessResult readiness) {
    }
}
