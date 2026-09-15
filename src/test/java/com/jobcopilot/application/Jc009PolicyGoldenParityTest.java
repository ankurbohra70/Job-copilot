package com.jobcopilot.application;

import com.jobcopilot.matching.MatchResult;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class Jc009PolicyGoldenParityTest {
    @Test void releasedRecommendationToDecisionMappingIsFrozen() {
        Map<MatchResult.Recommendation, ApplicationDecision> golden = Map.of(
                MatchResult.Recommendation.NOT_RECOMMENDED, ApplicationDecision.SKIP,
                MatchResult.Recommendation.WEAK_MATCH, ApplicationDecision.SAVE,
                MatchResult.Recommendation.GOOD_MATCH, ApplicationDecision.APPLY_VOLUME,
                MatchResult.Recommendation.STRONG_MATCH, ApplicationDecision.APPLY_PRECISION);
        golden.forEach((recommendation, expected) ->
                assertEquals(expected, ApplicationDecisionPolicy.from(recommendation)));
        assertEquals("application-decision-v1", ApplicationDecisionPolicy.VERSION);
    }

    @Test void releasedReadinessAndCheckStateNamesAreFrozen() {
        assertEquals(java.util.Set.of("READY", "NEEDS_USER", "NOT_READY"),
                java.util.Arrays.stream(ApplicationReadiness.values()).map(Enum::name)
                        .collect(java.util.stream.Collectors.toSet()));
        assertEquals(java.util.Set.of("PASS", "FAIL", "NEEDS_USER", "NOT_APPLICABLE"),
                java.util.Arrays.stream(ReadinessCheck.Status.values()).map(Enum::name)
                        .collect(java.util.stream.Collectors.toSet()));
    }
}
