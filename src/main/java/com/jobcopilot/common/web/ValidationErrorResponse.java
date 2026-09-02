package com.jobcopilot.common.web;

import java.util.Map;

public record ValidationErrorResponse(
        int status,
        String error,
        Map<String, String> fieldErrors
) {
}
