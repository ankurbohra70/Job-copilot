package com.jobcopilot.discovery;

public record JobSourceSyncResult(long runId, JobSourceSyncStatus status, String failureCode,
        Counters counters) {
    public record Counters(int discovered, int created, int updated, int unchanged, int closed,
            int reopened, int rankingReady, int unready) {
    }
}
