package com.jobcopilot.discovery;

public final class JobSourceApiExceptions {
    private JobSourceApiExceptions() {
    }

    public static class SourceNotFound extends RuntimeException {
        public SourceNotFound(long sourceId) { super("Job source " + sourceId + " was not found"); }
    }

    public static class DuplicateSource extends RuntimeException {
        public DuplicateSource() {
            super("A job source with this provider, region, and sourceKey already exists");
        }
    }

    public static class SourceDisabled extends RuntimeException {
        public SourceDisabled(long sourceId) { super("Job source " + sourceId + " is disabled"); }
    }

    public static class InvalidStoredSource extends RuntimeException {
        public InvalidStoredSource(long sourceId) {
            super("Job source " + sourceId + " cannot be synchronized with Lever");
        }
    }

    public static class UnsupportedProvider extends RuntimeException {
        public UnsupportedProvider() { super("Only LEVER is supported"); }
    }

    public static class InvalidSource extends RuntimeException {
        public InvalidSource(String message) { super(message); }
    }

    public static class InvalidQuery extends RuntimeException {
        public InvalidQuery(String message) { super(message); }
    }

    public static class RunNotFound extends RuntimeException {
        public RunNotFound(long sourceId, long runId) {
            super("Synchronization run " + runId + " was not found for job source " + sourceId);
        }
    }

    public static class ListingNotFound extends RuntimeException {
        public ListingNotFound(long sourceId, long listingId) {
            super("External job listing " + listingId + " was not found for job source " + sourceId);
        }
    }

    public static class PersistenceFailure extends RuntimeException {
        public PersistenceFailure() { super("Job source persistence failed"); }
    }

    public static class SynchronizationFailure extends RuntimeException {
        public SynchronizationFailure() { super("Job source synchronization failed"); }
    }
}
