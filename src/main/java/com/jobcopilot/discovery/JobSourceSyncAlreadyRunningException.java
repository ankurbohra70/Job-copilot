package com.jobcopilot.discovery;

public class JobSourceSyncAlreadyRunningException extends RuntimeException {
    JobSourceSyncAlreadyRunningException(long sourceId) {
        super("Job source " + sourceId + " already has a running synchronization");
    }
}
