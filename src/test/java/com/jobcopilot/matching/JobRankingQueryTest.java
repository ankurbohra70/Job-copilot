package com.jobcopilot.matching;

import com.jobcopilot.job.JobStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static com.jobcopilot.matching.MatchResult.Recommendation;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JobRankingQueryTest {
    @Test
    void omittedParametersResolveDefaultsWithoutPostScoreFilters() {
        JobRankingQuery query = JobRankingQuery.parse(null, null, null, null, null);

        assertEquals(JobRankingQuery.DEFAULT_STATUSES, query.statuses());
        assertEquals(Set.of(
                JobStatus.DISCOVERED,
                JobStatus.SHORTLISTED,
                JobStatus.APPLIED,
                JobStatus.INTERVIEWING
        ), query.statuses());
        assertTrue(query.statuses().stream().noneMatch(status ->
                status == JobStatus.OFFER || status == JobStatus.REJECTED || status == JobStatus.WITHDRAWN));
        assertNull(query.minScore());
        assertTrue(query.recommendations().isEmpty());
        assertEquals(0, query.page());
        assertEquals(20, query.size());
    }

    @Test
    void explicitStatusesCompletelyReplaceDefaultsAndDeduplicate() {
        JobRankingQuery offer = JobRankingQuery.parse(List.of("OFFER"), null, null, null, null);
        assertEquals(Set.of(JobStatus.OFFER), offer.statuses());

        JobRankingQuery rejected = JobRankingQuery.parse(List.of("REJECTED"), null, null, null, null);
        assertEquals(Set.of(JobStatus.REJECTED), rejected.statuses());

        JobRankingQuery withdrawn = JobRankingQuery.parse(List.of("WITHDRAWN"), null, null, null, null);
        assertEquals(Set.of(JobStatus.WITHDRAWN), withdrawn.statuses());

        JobRankingQuery mixed = JobRankingQuery.parse(List.of("DISCOVERED", "OFFER", "DISCOVERED"), null, null, null, null);
        assertEquals(Set.of(JobStatus.DISCOVERED, JobStatus.OFFER), mixed.statuses());
    }

    @Test
    void blankAndInvalidEnumsAreRejected() {
        assertEquals("status must not be blank",
                assertThrows(InvalidJobRankingQueryException.class,
                        () -> JobRankingQuery.parse(List.of(""), null, null, null, null)).getMessage());
        assertEquals("status must not be blank",
                assertThrows(InvalidJobRankingQueryException.class,
                        () -> JobRankingQuery.parse(List.of("DISCOVERED", "  "), null, null, null, null)).getMessage());
        assertEquals("Invalid value for status",
                assertThrows(InvalidJobRankingQueryException.class,
                        () -> JobRankingQuery.parse(List.of("discovered"), null, null, null, null)).getMessage());
        assertEquals("recommendation must not be blank",
                assertThrows(InvalidJobRankingQueryException.class,
                        () -> JobRankingQuery.parse(null, List.of(""), null, null, null)).getMessage());
        assertEquals("Invalid value for recommendation",
                assertThrows(InvalidJobRankingQueryException.class,
                        () -> JobRankingQuery.parse(null, List.of("strong_match"), null, null, null)).getMessage());
    }

    @Test
    void recommendationsDeduplicateAndRetainEachTier() {
        JobRankingQuery query = JobRankingQuery.parse(
                null,
                List.of("STRONG_MATCH", "GOOD_MATCH", "STRONG_MATCH", "WEAK_MATCH", "NOT_RECOMMENDED"),
                null,
                null,
                null
        );
        assertEquals(Set.of(
                Recommendation.STRONG_MATCH,
                Recommendation.GOOD_MATCH,
                Recommendation.WEAK_MATCH,
                Recommendation.NOT_RECOMMENDED
        ), query.recommendations());
    }

    @Test
    void minScoreAcceptsInclusiveRangeIncludingScaleVariants() {
        for (String value : List.of("0", "0.0", "44.99", "45", "64.99", "65", "65.00", "79.99", "80", "100", "100.0", "6.5E1")) {
            assertEquals(0, JobRankingQuery.parse(null, null, List.of(value), null, null)
                    .minScore().compareTo(new BigDecimal(value)));
        }
    }

    @Test
    void minScoreRejectsBlankMalformedRangeAndRepeats() {
        assertEquals("minScore must not be blank",
                assertThrows(InvalidJobRankingQueryException.class,
                        () -> JobRankingQuery.parse(null, null, List.of(""), null, null)).getMessage());
        assertEquals("minScore is malformed",
                assertThrows(InvalidJobRankingQueryException.class,
                        () -> JobRankingQuery.parse(null, null, List.of("sixty"), null, null)).getMessage());
        assertEquals("minScore is malformed",
                assertThrows(InvalidJobRankingQueryException.class,
                        () -> JobRankingQuery.parse(null, null, List.of("NaN"), null, null)).getMessage());
        assertEquals("minScore is malformed",
                assertThrows(InvalidJobRankingQueryException.class,
                        () -> JobRankingQuery.parse(null, null, List.of("Infinity"), null, null)).getMessage());
        assertEquals("minScore must be between 0 and 100",
                assertThrows(InvalidJobRankingQueryException.class,
                        () -> JobRankingQuery.parse(null, null, List.of("-0.01"), null, null)).getMessage());
        assertEquals("minScore must be between 0 and 100",
                assertThrows(InvalidJobRankingQueryException.class,
                        () -> JobRankingQuery.parse(null, null, List.of("100.01"), null, null)).getMessage());
        assertEquals("minScore must not be specified more than once",
                assertThrows(InvalidJobRankingQueryException.class,
                        () -> JobRankingQuery.parse(null, null, List.of("65", "70"), null, null)).getMessage());
    }

    @Test
    void presentEmptyParameterMapValuesAreInvalid() {
        assertEquals("minScore must not be blank",
                assertThrows(InvalidJobRankingQueryException.class,
                        () -> JobRankingQuery.from(java.util.Map.of("minScore", new String[] {""}))).getMessage());
        assertEquals("status must not be blank",
                assertThrows(InvalidJobRankingQueryException.class,
                        () -> JobRankingQuery.from(java.util.Map.of("status", new String[] {""}))).getMessage());
    }

    @Test
    void paginationDefaultsAndScalarRepeats() {
        JobRankingQuery sized = JobRankingQuery.parse(null, null, null, List.of("3"), List.of("100"));
        assertEquals(3, sized.page());
        assertEquals(100, sized.size());

        assertEquals("page must be at least 0",
                assertThrows(InvalidJobRankingQueryException.class,
                        () -> JobRankingQuery.parse(null, null, null, List.of("-1"), null)).getMessage());
        assertEquals("size must be between 1 and 100",
                assertThrows(InvalidJobRankingQueryException.class,
                        () -> JobRankingQuery.parse(null, null, null, null, List.of("0"))).getMessage());
        assertEquals("size must be between 1 and 100",
                assertThrows(InvalidJobRankingQueryException.class,
                        () -> JobRankingQuery.parse(null, null, null, null, List.of("101"))).getMessage());
        assertEquals("page must not be specified more than once",
                assertThrows(InvalidJobRankingQueryException.class,
                        () -> JobRankingQuery.parse(null, null, null, List.of("0", "1"), null)).getMessage());
        assertEquals("size must not be specified more than once",
                assertThrows(InvalidJobRankingQueryException.class,
                        () -> JobRankingQuery.parse(null, null, null, null, List.of("20", "10"))).getMessage());
        assertEquals("Invalid value for page",
                assertThrows(InvalidJobRankingQueryException.class,
                        () -> JobRankingQuery.parse(null, null, null, List.of("first"), null)).getMessage());
        assertEquals("Invalid value for size",
                assertThrows(InvalidJobRankingQueryException.class,
                        () -> JobRankingQuery.parse(null, null, null, null, List.of("twenty"))).getMessage());
    }
}
