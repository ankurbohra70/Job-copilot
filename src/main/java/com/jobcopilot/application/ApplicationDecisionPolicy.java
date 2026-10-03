package com.jobcopilot.application;

import com.jobcopilot.matching.MatchResult;

/** Versioned policy translating an existing match recommendation into an application action. */
public final class ApplicationDecisionPolicy {
    public static final String VERSION = "application-decision-v1";
    private ApplicationDecisionPolicy() {}
    public static ApplicationDecision from(MatchResult.Recommendation recommendation) {
        return switch (recommendation) {
            case STRONG_MATCH -> ApplicationDecision.APPLY_PRECISION;
            case GOOD_MATCH -> ApplicationDecision.APPLY_VOLUME;
            case WEAK_MATCH -> ApplicationDecision.SAVE;
            case NOT_RECOMMENDED -> ApplicationDecision.SKIP;
        };
    }
}
