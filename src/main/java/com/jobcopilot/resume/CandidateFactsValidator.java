package com.jobcopilot.resume;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import static com.jobcopilot.resume.ProfessionalLink.Type.*;

/** Shared domain validation for extracted and user-supplied links; never fetches a URI. */
public final class CandidateFactsValidator {
    private CandidateFactsValidator() {}
    private static final Set<String> GITHUB_RESERVED = Set.of("about", "login", "signup", "settings", "features",
            "topics", "trending", "explore", "marketplace", "collections", "orgs", "organizations", "enterprise",
            "pricing", "sponsors", "contact", "security", "site", "search", "apps", "events", "notifications");
    private static final Set<String> LEETCODE_RESERVED = Set.of("problems", "contest", "discuss", "explore", "accounts",
            "problemset", "studyplan", "store", "premium", "jobs", "subscribe", "login", "signup");

    public static String normalizeUrl(String raw) {
        if (raw == null) throw new IllegalArgumentException("Professional link URL is required");
        String value = raw.strip();
        if (value.length() > 2048 || value.isEmpty() || value.chars().anyMatch(c -> Character.isISOControl(c) || Character.isWhitespace(c)))
            throw new IllegalArgumentException("Professional link URL is invalid");
        if (!value.matches("(?i)^[a-z][a-z0-9+.-]*:.*")) value = "https://" + value;
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme().toLowerCase(Locale.ROOT), host = uri.getHost();
            if (!(scheme.equals("http") || scheme.equals("https")) || host == null || !host.contains(".")
                    || host.endsWith(".") || uri.getPort() > 65535)
                throw new IllegalArgumentException("Professional links must be HTTP(S) URLs with a valid host");
            String authority = host.toLowerCase(Locale.ROOT);
            if (uri.getPort() != -1 && !(scheme.equals("https") && uri.getPort() == 443)
                    && !(scheme.equals("http") && uri.getPort() == 80)) authority += ":" + uri.getPort();
            String path = uri.getRawPath() == null ? "" : uri.getRawPath();
            String result = scheme + "://" + authority + path
                    + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
            if (result.length() > 2048) throw new IllegalArgumentException("Professional link URL is too long");
            return result;
        } catch (URISyntaxException exception) { throw new IllegalArgumentException("Professional link URL is invalid"); }
    }
    public static ProfessionalLink.Type classify(String normalized) {
        URI uri = URI.create(normalized);
        String host = uri.getHost().toLowerCase(Locale.ROOT), path = uri.getPath();
        String[] parts = path == null ? new String[0] : path.replaceAll("^/+|/+$", "").split("/");
        if (host.equals("linkedin.com") || host.endsWith(".linkedin.com")) {
            return parts.length == 2 && parts[0].equals("in") && parts[1].matches("[\\p{L}\\p{N}_-]+") ? LINKEDIN : OTHER;
        }
        if (host.equals("github.com") || host.equals("www.github.com")) {
            return parts.length == 1 && parts[0].matches("[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?")
                    && !GITHUB_RESERVED.contains(parts[0].toLowerCase(Locale.ROOT)) ? GITHUB : OTHER;
        }
        if (host.equals("leetcode.com") || host.equals("www.leetcode.com")) {
            String user = parts.length == 1 ? parts[0] : parts.length == 2 && parts[0].equals("u") ? parts[1] : "";
            return user.matches("[A-Za-z0-9_-]+") && !LEETCODE_RESERVED.contains(user.toLowerCase(Locale.ROOT)) ? LEETCODE : OTHER;
        }
        return WEBSITE;
    }
    public static List<ProfessionalLink> canonicalLinks(List<ProfessionalLink> links) {
        if (links == null) return List.of();
        if (links.size() > 20) throw new IllegalArgumentException("professionalLinks exceeds 20 items");
        var unique = new LinkedHashMap<String, ProfessionalLink>();
        for (var link : links) {
            if (link == null || link.type() == null) throw new IllegalArgumentException("Professional link type is required");
            String normalized = normalizeUrl(link.url());
            var classified = classify(normalized);
            if (link.type() != WEBSITE && link.type() != OTHER && link.type() != classified)
                throw new IllegalArgumentException("Professional link type does not match a personal profile URL");
            unique.putIfAbsent(normalized, new ProfessionalLink(link.type(), normalized));
        }
        return List.copyOf(unique.values());
    }
}
