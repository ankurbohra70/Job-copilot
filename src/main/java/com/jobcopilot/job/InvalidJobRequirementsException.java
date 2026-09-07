package com.jobcopilot.job;
public class InvalidJobRequirementsException extends RuntimeException {
    public InvalidJobRequirementsException() { super("Normalized skills must contain 1 to 100 characters without control characters"); }
}
