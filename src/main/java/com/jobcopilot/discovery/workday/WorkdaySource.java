package com.jobcopilot.discovery.workday;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Canonical public external-career-site coordinates. Arbitrary/custom hosts are not accepted in Phase 1. */
public record WorkdaySource(String endpointHost, String tenantKey, String siteKey, String locale) {
    private static final Pattern HOST = Pattern.compile(
            "[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+");
    private static final Pattern TENANT = Pattern.compile("[a-z0-9][a-z0-9_-]{0,99}");
    private static final Pattern SITE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,99}");
    private static final Pattern LOCALE = Pattern.compile("[a-z]{2,8}(?:-(?:[A-Z]{2}|[A-Za-z0-9]{1,8}))*");

    public WorkdaySource {
        endpointHost = required(endpointHost, 255, "endpointHost");
        tenantKey = required(tenantKey, 100, "tenantKey");
        siteKey = required(siteKey, 100, "siteKey");
        locale = canonicalLocale(required(locale, 35, "locale"));
        if (!endpointHost.equals(endpointHost.toLowerCase(Locale.ROOT)) || !HOST.matcher(endpointHost).matches()
                || !(endpointHost.endsWith(".myworkdayjobs.com")
                    || endpointHost.endsWith(".myworkdaysite.com"))) {
            throw new IllegalArgumentException("endpointHost is not an approved public Workday host");
        }
        if (!tenantKey.equals(tenantKey.toLowerCase(Locale.ROOT)) || !TENANT.matcher(tenantKey).matches()) {
            throw new IllegalArgumentException("tenantKey is invalid");
        }
        if (!SITE.matcher(siteKey).matches()) throw new IllegalArgumentException("siteKey is invalid");
        if (!LOCALE.matcher(locale).matches()) throw new IllegalArgumentException("locale is invalid");
    }

    private static String required(String value, int max, String name) {
        Objects.requireNonNull(value, name + " is required");
        if (value.isEmpty() || value.length() > max || !value.equals(value.strip())
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return value;
    }

    private static String canonicalLocale(String value) {
        String[] parts = value.split("-", -1);
        if (parts.length == 0) return value;
        parts[0] = parts[0].toLowerCase(Locale.ROOT);
        if (parts.length > 1 && parts[1].length() == 2) parts[1] = parts[1].toUpperCase(Locale.ROOT);
        return String.join("-", parts);
    }
}
