package com.jobcopilot.discovery.workday;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("job-discovery.workday")
public record WorkdayClientProperties(
        @DefaultValue("5s") Duration connectTimeout,
        @DefaultValue("20s") Duration requestTimeout,
        @DefaultValue("5242880") long maxSearchResponseBytes,
        @DefaultValue("2097152") long maxDetailResponseBytes) {
    public WorkdayClientProperties {
        positive(connectTimeout, "job-discovery.workday.connect-timeout");
        positive(requestTimeout, "job-discovery.workday.request-timeout");
        bytes(maxSearchResponseBytes, "job-discovery.workday.max-search-response-bytes");
        bytes(maxDetailResponseBytes, "job-discovery.workday.max-detail-response-bytes");
    }
    private static void positive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative())
            throw new IllegalArgumentException(name + " must be positive");
        try {
            long millis = value.toMillis();
            if (millis < 1 || millis > Integer.MAX_VALUE) throw supported(name);
        } catch (ArithmeticException tooLarge) { throw supported(name); }
    }
    private static void bytes(long value, String name) {
        if (value < 1 || value > Integer.MAX_VALUE)
            throw new IllegalArgumentException(name + " must be between 1 and " + Integer.MAX_VALUE);
    }
    private static IllegalArgumentException supported(String name) {
        return new IllegalArgumentException(name + " must be between 1ms and " + Integer.MAX_VALUE + "ms");
    }
}
