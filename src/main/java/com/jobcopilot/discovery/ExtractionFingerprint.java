package com.jobcopilot.discovery;

import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
final class ExtractionFingerprint {
    String calculate(String extractorVersion, String description) {
        Objects.requireNonNull(extractorVersion, "extractorVersion is required");
        Objects.requireNonNull(description, "description is required");
        if (!extractorVersion.matches("[a-z0-9][a-z0-9-]{0,31}")) {
            throw new IllegalArgumentException("extractorVersion is invalid");
        }
        return extractorVersion + ":sha256:"
                + ProviderContentDigest.hash("description-v1", List.of(description));
    }
}
