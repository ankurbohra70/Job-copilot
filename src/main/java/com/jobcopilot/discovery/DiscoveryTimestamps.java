package com.jobcopilot.discovery;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

final class DiscoveryTimestamps {
    private DiscoveryTimestamps() {
    }

    static LocalDateTime toDatabasePrecision(LocalDateTime value, String name) {
        return Objects.requireNonNull(value, name + " is required").truncatedTo(ChronoUnit.MICROS);
    }
}
