package com.jobcopilot.discovery.lever;

import java.util.Locale;
import java.util.Objects;

/**
 * A source identity that has crossed the Phase-1 canonicalization boundary and is safe as one Lever path segment.
 * Dot segments are the only additional transport restriction because HTTP clients normalize them as traversal.
 */
public record LeverSource(LeverRegion region, String sourceKey) {
    private static final int MAX_SOURCE_KEY_LENGTH = 100;

    public LeverSource {
        Objects.requireNonNull(region, "region is required");
        Objects.requireNonNull(sourceKey, "sourceKey is required");
        if (sourceKey.isEmpty() || sourceKey.length() > MAX_SOURCE_KEY_LENGTH
                || sourceKey.charAt(0) == ' ' || sourceKey.charAt(sourceKey.length() - 1) == ' '
                || sourceKey.chars().anyMatch(Character::isISOControl)
                || !sourceKey.equals(sourceKey.toLowerCase(Locale.ROOT))
                || sourceKey.equals(".") || sourceKey.equals("..")) {
            throw new IllegalArgumentException("sourceKey must already be canonical");
        }
    }
}
