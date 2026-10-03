package com.jobcopilot.discovery.workday;

import java.net.URI;
import java.util.Objects;

/** Immutable provider data. No field is yet designated as the canonical external job identity. */
public record WorkdayJobDetail(String opaqueId, String title, String descriptionHtml, String location,
        String postedOn, String startDate, String timeType, String remoteType, String jobReqId,
        String jobPostingId, String jobPostingSiteId, URI externalUrl, boolean posted, boolean canApply) {
    public WorkdayJobDetail {
        Objects.requireNonNull(opaqueId);
        Objects.requireNonNull(title);
        Objects.requireNonNull(descriptionHtml);
        Objects.requireNonNull(externalUrl);
    }

    @Override public String toString() { return "WorkdayJobDetail[redacted]"; }
}
