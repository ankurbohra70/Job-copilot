package com.jobcopilot.matching;

import java.math.BigDecimal;
import java.util.Map;
import static com.jobcopilot.matching.MatchResult.*;

public record MatchingPolicy(String version, Map<Category, Integer> weights,
        BigDecimal requiredShare, BigDecimal missingRequiredCap, BigDecimal noneRequiredCap,
        BigDecimal strongThreshold, BigDecimal goodThreshold, BigDecimal weakThreshold) {
    public static MatchingPolicy v1() {
        return new MatchingPolicy("deterministic-v1", Map.of(Category.SKILLS,60, Category.EXPERIENCE,30,
                Category.ROLE,5, Category.KEYWORDS,5), new BigDecimal("0.8"), new BigDecimal("79"),
                new BigDecimal("49"), new BigDecimal("80"), new BigDecimal("65"), new BigDecimal("45"));
    }
    public MatchingPolicy {
        weights = Map.copyOf(weights);
        if (weights.size() != Category.values().length || weights.values().stream().anyMatch(w -> w <= 0))
            throw new IllegalArgumentException("Every category needs a positive weight");
        if (requiredShare.compareTo(BigDecimal.ZERO) < 0 || requiredShare.compareTo(BigDecimal.ONE) > 0)
            throw new IllegalArgumentException("Invalid required share");
    }
    public Recommendation recommendation(BigDecimal score) {
        return score.compareTo(strongThreshold) >= 0 ? Recommendation.STRONG_MATCH :
                score.compareTo(goodThreshold) >= 0 ? Recommendation.GOOD_MATCH :
                score.compareTo(weakThreshold) >= 0 ? Recommendation.WEAK_MATCH : Recommendation.NOT_RECOMMENDED;
    }
}

