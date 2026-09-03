package com.jobcopilot.common.web;

public record ApiErrorResponse(
        int status,
        String error,
        String message
) {
}
