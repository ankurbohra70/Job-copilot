package com.jobcopilot.discovery.workday;

import java.util.List;
import java.util.Objects;

public record WorkdaySearchPosting(String title, String externalPath, String locationsText,
        String postedOn, String remoteType, List<String> bulletFields) {
    public WorkdaySearchPosting {
        Objects.requireNonNull(title);
        Objects.requireNonNull(externalPath);
        bulletFields = List.copyOf(Objects.requireNonNull(bulletFields));
    }

    @Override public String toString() { return "WorkdaySearchPosting[redacted]"; }
}
