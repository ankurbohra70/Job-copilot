package com.jobcopilot.discovery.workday;

/** One observed CXS offset page. Live evidence on 2026-09-16 showed a hard maximum limit of 20. */
public record WorkdayPageRequest(int offset, int limit) {
    public static final int MAX_LIMIT = 20;
    public static final int MAX_OFFSET = 1_000_000;

    public WorkdayPageRequest {
        if (offset < 0 || offset > MAX_OFFSET)
            throw new IllegalArgumentException("offset must be between 0 and " + MAX_OFFSET);
        if (limit < 1 || limit > MAX_LIMIT)
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
    }
}
