package com.jobcopilot.discovery;

record JobSourceSyncCounters(int discovered, int created, int updated, int unchanged,
        int closed, int reopened, int rankingReady, int unready) {
    JobSourceSyncCounters {
        if (discovered < 0 || created < 0 || updated < 0 || unchanged < 0
                || closed < 0 || reopened < 0 || rankingReady < 0 || unready < 0) {
            throw new IllegalArgumentException("synchronization counters must be non-negative");
        }
    }

    static JobSourceSyncCounters zero() {
        return new JobSourceSyncCounters(0, 0, 0, 0, 0, 0, 0, 0);
    }
}
