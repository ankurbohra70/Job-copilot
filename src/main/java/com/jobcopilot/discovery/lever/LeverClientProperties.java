package com.jobcopilot.discovery.lever;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("job-discovery.lever")
public record LeverClientProperties(
        @DefaultValue("5s") Duration connectTimeout,
        @DefaultValue("20s") Duration requestTimeout,
        @DefaultValue("10485760") long maxResponseBytes) {

    public LeverClientProperties {
        requirePositive(connectTimeout, "job-discovery.lever.connect-timeout");
        requirePositive(requestTimeout, "job-discovery.lever.request-timeout");
        if (maxResponseBytes <= 0 || maxResponseBytes > Integer.MAX_VALUE)
            throw new IllegalArgumentException(
                    "job-discovery.lever.max-response-bytes must be between 1 and " + Integer.MAX_VALUE);
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative())
            throw new IllegalArgumentException(name + " must be positive");
        final long millis;
        try {
            millis = value.toMillis();
        } catch (ArithmeticException tooLarge) {
            throw supportedDuration(name);
        }
        if (millis < 1 || millis > Integer.MAX_VALUE)
            throw supportedDuration(name);
    }

    private static IllegalArgumentException supportedDuration(String name) {
        return new IllegalArgumentException(name + " must be between 1ms and " + Integer.MAX_VALUE + "ms");
    }
}
