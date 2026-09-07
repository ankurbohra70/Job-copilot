package com.jobcopilot.matching;

import com.jobcopilot.job.JobStatus;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record JobRankingQuery(
        Set<JobStatus> statuses,
        BigDecimal minScore,
        Set<MatchResult.Recommendation> recommendations,
        int page,
        int size
) {
    public static final int DEFAULT_PAGE = 0;
    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;
    static final BigDecimal MIN_SCORE_MINIMUM = BigDecimal.ZERO;
    static final BigDecimal MIN_SCORE_MAXIMUM = new BigDecimal("100");

    public static final Set<JobStatus> DEFAULT_STATUSES = Collections.unmodifiableSet(EnumSet.of(
            JobStatus.DISCOVERED,
            JobStatus.SHORTLISTED,
            JobStatus.APPLIED,
            JobStatus.INTERVIEWING
    ));

    public JobRankingQuery {
        if (statuses == null || statuses.isEmpty()) {
            throw new InvalidJobRankingQueryException("status must not be blank");
        }
        statuses = Set.copyOf(statuses);
        recommendations = recommendations == null ? Set.of() : Set.copyOf(recommendations);
    }

    public static JobRankingQuery from(Map<String, String[]> parameterMap) {
        return parse(
                values(parameterMap, "status"),
                values(parameterMap, "recommendation"),
                values(parameterMap, "minScore"),
                values(parameterMap, "page"),
                values(parameterMap, "size")
        );
    }

    public static JobRankingQuery parse(
            List<String> status,
            List<String> recommendation,
            List<String> minScore,
            List<String> page,
            List<String> size
    ) {
        return new JobRankingQuery(
                parseStatuses(status),
                parseMinScore(minScore),
                parseRecommendations(recommendation),
                parsePage(page),
                parseSize(size)
        );
    }

    private static List<String> values(Map<String, String[]> parameterMap, String name) {
        if (parameterMap == null || !parameterMap.containsKey(name)) {
            return null;
        }
        String[] raw = parameterMap.get(name);
        if (raw == null) {
            return List.of();
        }
        return Arrays.asList(raw);
    }

    private static Set<JobStatus> parseStatuses(List<String> values) {
        if (values == null) {
            return DEFAULT_STATUSES;
        }
        if (values.isEmpty()) {
            throw new InvalidJobRankingQueryException("status must not be blank");
        }
        Set<JobStatus> resolved = new LinkedHashSet<>();
        for (String value : values) {
            resolved.add(parseEnum(JobStatus.class, value, "status"));
        }
        return resolved;
    }

    private static Set<MatchResult.Recommendation> parseRecommendations(List<String> values) {
        if (values == null) {
            return Set.of();
        }
        if (values.isEmpty()) {
            throw new InvalidJobRankingQueryException("recommendation must not be blank");
        }
        Set<MatchResult.Recommendation> resolved = EnumSet.noneOf(MatchResult.Recommendation.class);
        for (String value : values) {
            resolved.add(parseEnum(MatchResult.Recommendation.class, value, "recommendation"));
        }
        return resolved;
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value, String name) {
        if (value == null || value.isBlank()) {
            throw new InvalidJobRankingQueryException(name + " must not be blank");
        }
        try {
            return Enum.valueOf(type, value.strip());
        } catch (IllegalArgumentException exception) {
            throw new InvalidJobRankingQueryException("Invalid value for " + name);
        }
    }

    private static BigDecimal parseMinScore(List<String> values) {
        if (values == null) {
            return null;
        }
        if (values.isEmpty()) {
            throw new InvalidJobRankingQueryException("minScore must not be blank");
        }
        String raw = exclusiveScalar("minScore", values);
        if (raw.isBlank()) {
            throw new InvalidJobRankingQueryException("minScore must not be blank");
        }
        BigDecimal parsed;
        try {
            parsed = new BigDecimal(raw.strip());
        } catch (NumberFormatException exception) {
            throw new InvalidJobRankingQueryException("minScore is malformed");
        }
        if (parsed.compareTo(MIN_SCORE_MINIMUM) < 0 || parsed.compareTo(MIN_SCORE_MAXIMUM) > 0) {
            throw new InvalidJobRankingQueryException("minScore must be between 0 and 100");
        }
        return parsed;
    }

    private static int parsePage(List<String> values) {
        int page = parseNonNegativeInt("page", values, DEFAULT_PAGE);
        if (page < 0) {
            throw new InvalidJobRankingQueryException("page must be at least 0");
        }
        return page;
    }

    private static int parseSize(List<String> values) {
        int size = parseNonNegativeInt("size", values, DEFAULT_SIZE);
        if (size < 1 || size > MAX_SIZE) {
            throw new InvalidJobRankingQueryException("size must be between 1 and " + MAX_SIZE);
        }
        return size;
    }

    private static int parseNonNegativeInt(String name, List<String> values, int defaultValue) {
        if (values == null) {
            return defaultValue;
        }
        if (values.isEmpty()) {
            throw new InvalidJobRankingQueryException("Invalid value for " + name);
        }
        String raw = exclusiveScalar(name, values);
        if (raw.isBlank()) {
            throw new InvalidJobRankingQueryException("Invalid value for " + name);
        }
        try {
            return Integer.parseInt(raw.strip());
        } catch (NumberFormatException exception) {
            throw new InvalidJobRankingQueryException("Invalid value for " + name);
        }
    }

    private static String exclusiveScalar(String name, List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        if (values.size() > 1) {
            throw new InvalidJobRankingQueryException(name + " must not be specified more than once");
        }
        return values.getFirst();
    }
}
