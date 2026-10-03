package com.jobcopilot.application;

public record ReadinessCheck(String code, Status status, String message) {
    public enum Status { PASS, FAIL, NEEDS_USER, NOT_APPLICABLE }
}
