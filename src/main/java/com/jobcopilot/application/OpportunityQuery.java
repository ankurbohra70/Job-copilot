package com.jobcopilot.application;

import com.jobcopilot.matching.InvalidJobRankingQueryException;
import com.jobcopilot.matching.JobRankingQuery;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

record OpportunityQuery(JobRankingQuery ranking, Set<ApplicationReadiness> readiness) {
    OpportunityQuery {
        readiness = readiness == null ? Set.of() : Set.copyOf(readiness);
    }

    static OpportunityQuery from(Map<String, String[]> parameters) {
        Map<String, String[]> rankingParameters = new LinkedHashMap<>(parameters);
        if (rankingParameters.remove("status") != null)
            throw new InvalidJobRankingQueryException("status is fixed for live opportunities");
        String[] readinessValues = rankingParameters.remove("readiness");
        Set<ApplicationReadiness> readiness = EnumSet.noneOf(ApplicationReadiness.class);
        if (readinessValues != null) {
            if (readinessValues.length == 0)
                throw new InvalidJobRankingQueryException("readiness must not be blank");
            for (String value : Arrays.asList(readinessValues)) {
                if (value == null || value.isBlank())
                    throw new InvalidJobRankingQueryException("readiness must not be blank");
                try {
                    readiness.add(ApplicationReadiness.valueOf(value.strip()));
                } catch (IllegalArgumentException exception) {
                    throw new InvalidJobRankingQueryException("Invalid value for readiness");
                }
            }
        }
        return new OpportunityQuery(JobRankingQuery.from(rankingParameters), readiness);
    }

    boolean matches(ApplicationReadiness value) {
        return readiness.isEmpty() || readiness.contains(value);
    }
}
