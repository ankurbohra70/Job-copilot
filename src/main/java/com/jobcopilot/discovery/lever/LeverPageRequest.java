package com.jobcopilot.discovery.lever;

/** One Lever offset page. Page traversal is deliberately owned by later orchestration. */
public record LeverPageRequest(int skip, int limit) {
    public LeverPageRequest {
        if (skip < 0) throw new IllegalArgumentException("skip must not be negative");
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit must be between 1 and 100");
    }
}
