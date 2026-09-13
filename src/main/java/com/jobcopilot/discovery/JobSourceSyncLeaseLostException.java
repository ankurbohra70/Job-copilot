package com.jobcopilot.discovery;

final class JobSourceSyncLeaseLostException extends RuntimeException {
    JobSourceSyncLeaseLostException(long sourceId, long runId) {
        super("Synchronization run " + runId + " is no longer authoritative for job source " + sourceId);
    }
}
