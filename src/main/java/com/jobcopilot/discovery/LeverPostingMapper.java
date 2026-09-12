package com.jobcopilot.discovery;

import com.jobcopilot.discovery.lever.LeverPosting;
import java.util.LinkedHashSet;
import org.springframework.stereotype.Component;

@Component
final class LeverPostingMapper {
    private static final int TITLE_MAX = 255;
    private static final int LOCATION_MAX = 255;
    private static final int URL_MAX = 2048;
    private final LeverDescriptionAssembler descriptions;
    private final ProviderContentDigest contentDigests;
    private final ExtractionFingerprint extractionFingerprints;

    LeverPostingMapper(LeverDescriptionAssembler descriptions, ProviderContentDigest contentDigests,
            ExtractionFingerprint extractionFingerprints) {
        this.descriptions = descriptions;
        this.contentDigests = contentDigests;
        this.extractionFingerprints = extractionFingerprints;
    }

    LeverMappedListing map(JobSource source, LeverPosting posting, String extractorVersion) {
        String title = requiredSingleLine(posting.title(), TITLE_MAX, "title");
        String location = location(posting.categories());
        String description = descriptions.assemble(posting.content());
        String hostedUrl = bounded(posting.hostedUrl().toString(), URL_MAX, "hostedUrl");
        String applyUrl = bounded(posting.applyUrl().toString(), URL_MAX, "applyUrl");
        String providerDigest = contentDigests.calculate(title, location, description, hostedUrl, applyUrl);
        String extractionFingerprint = description == null ? null
                : extractionFingerprints.calculate(extractorVersion, description);
        return new LeverMappedListing(posting.externalId(), title, source.companyName(), location,
                hostedUrl, applyUrl, description, providerDigest, extractionFingerprint);
    }

    private static String location(LeverPosting.Categories categories) {
        String primary = optionalSingleLine(categories.location(), LOCATION_MAX, "location");
        if (primary != null) return primary;
        LinkedHashSet<String> locations = new LinkedHashSet<>();
        for (String value : categories.allLocations()) {
            String normalized = optionalSingleLine(value, LOCATION_MAX, "allLocations");
            if (normalized != null) locations.add(normalized);
        }
        if (locations.isEmpty()) return null;
        return bounded(String.join(" / ", locations), LOCATION_MAX, "location");
    }

    private static String requiredSingleLine(String value, int maximum, String name) {
        String normalized = optionalSingleLine(value, maximum, name);
        if (normalized == null) throw new IllegalArgumentException(name + " is required");
        return normalized;
    }

    private static String optionalSingleLine(String value, int maximum, String name) {
        if (value == null) return null;
        String normalized = value.replace("\r\n", " ").replace('\r', ' ').replace('\n', ' ').replace('\t', ' ')
                .strip().replaceAll(" +", " ");
        if (normalized.isEmpty()) return null;
        for (int index = 0; index < normalized.length(); index++) {
            if (Character.isISOControl(normalized.charAt(index))) {
                throw new IllegalArgumentException(name + " contains unsupported controls");
            }
        }
        return bounded(normalized, maximum, name);
    }

    private static String bounded(String value, int maximum, String name) {
        if (value.length() > maximum) throw new IllegalArgumentException(name + " exceeds " + maximum);
        return value;
    }
}
