package com.jobcopilot.application;

import com.jobcopilot.matching.InvalidJobRankingQueryException;
import com.jobcopilot.matching.MatchResult;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OpportunityQueryTest {
    @Test void supportsBoundedRankingAndReadinessFilters() {
        OpportunityQuery query = OpportunityQuery.from(Map.of(
                "minScore", new String[]{"65"},
                "recommendation", new String[]{"GOOD_MATCH", "STRONG_MATCH"},
                "readiness", new String[]{"READY"},
                "page", new String[]{"1"},
                "size", new String[]{"10"}));

        assertEquals(1, query.ranking().page());
        assertEquals(10, query.ranking().size());
        assertEquals(2, query.ranking().recommendations().size());
        assertTrue(query.matches(ApplicationReadiness.READY));
        assertFalse(query.matches(ApplicationReadiness.NEEDS_USER));
    }

    @Test void rejectsStatusOverrideAndInvalidReadiness() {
        assertThrows(InvalidJobRankingQueryException.class,
                () -> OpportunityQuery.from(Map.of("status", new String[]{"APPLIED"})));
        assertThrows(InvalidJobRankingQueryException.class,
                () -> OpportunityQuery.from(Map.of("readiness", new String[]{"UNKNOWN"})));
    }
}
