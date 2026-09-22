package com.jobcopilot.discovery.workday;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;

/** A search-returned CXS detail path. It remains relative and cannot select another host. */
public record WorkdayDetailRequest(String externalPath) {
    public WorkdayDetailRequest {
        Objects.requireNonNull(externalPath, "externalPath is required");
        String lowerPath = externalPath.toLowerCase(Locale.ROOT);
        if (externalPath.length() <= "/job/".length() || externalPath.length() > 2048
                || !externalPath.equals(externalPath.strip())
                || externalPath.chars().anyMatch(Character::isISOControl)
                || !externalPath.startsWith("/job/") || externalPath.contains("?") || externalPath.contains("#")
                || externalPath.contains("\\") || externalPath.contains("//")
                || lowerPath.contains("%2e") || lowerPath.contains("%2f") || lowerPath.contains("%5c")
                || lowerPath.contains("%25")
                || hasDotSegment(externalPath)) {
            throw new IllegalArgumentException("externalPath is invalid");
        }
        URI parsed = URI.create(externalPath);
        if (!externalPath.equals(parsed.getRawPath()) || parsed.getRawQuery() != null
                || parsed.getRawFragment() != null) {
            throw new IllegalArgumentException("externalPath is invalid");
        }
    }

    private static boolean hasDotSegment(String value) {
        for (String part : value.split("/", -1)) if (part.equals(".") || part.equals("..")) return true;
        return false;
    }

    @Override public String toString() { return "WorkdayDetailRequest[redacted]"; }
}
