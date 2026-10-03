package com.jobcopilot.discovery.workday;

import java.net.URI;
import java.util.Objects;

final class WorkdayEndpointResolver {
    private final URI testBase;

    WorkdayEndpointResolver() { this.testBase = null; }

    /** Test-only base must end in /wday/cxs/ and never participates in production source validation. */
    WorkdayEndpointResolver(URI testBase) {
        Objects.requireNonNull(testBase, "base URI is required");
        String scheme = testBase.getScheme();
        if (!testBase.isAbsolute() || testBase.getHost() == null
                || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                || testBase.getRawUserInfo() != null || testBase.getPort() < -1
                || testBase.getRawQuery() != null || testBase.getRawFragment() != null
                || !testBase.getRawPath().endsWith("/wday/cxs/")) {
            throw new IllegalArgumentException("base URI must end with /wday/cxs/");
        }
        this.testBase = testBase;
    }

    URI search(WorkdaySource source) {
        Objects.requireNonNull(source);
        return URI.create(prefix(source) + source.tenantKey() + "/" + source.siteKey() + "/jobs");
    }

    URI detail(WorkdaySource source, WorkdayDetailRequest detail) {
        Objects.requireNonNull(source); Objects.requireNonNull(detail);
        return URI.create(prefix(source) + source.tenantKey() + "/" + source.siteKey()
                + detail.externalPath());
    }

    private String prefix(WorkdaySource source) {
        return testBase == null ? "https://" + source.endpointHost() + "/wday/cxs/" : testBase.toASCIIString();
    }
}
