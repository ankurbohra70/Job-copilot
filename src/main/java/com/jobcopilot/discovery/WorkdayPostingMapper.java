package com.jobcopilot.discovery;

import com.jobcopilot.discovery.workday.WorkdayTraversal;
import org.springframework.stereotype.Component;

@Component
final class WorkdayPostingMapper {
    private final ProviderContentDigest digests;
    private final ExtractionFingerprint fingerprints;

    WorkdayPostingMapper(ProviderContentDigest digests, ExtractionFingerprint fingerprints) {
        this.digests = digests;
        this.fingerprints = fingerprints;
    }

    MappedExternalJobListing map(JobSource source, WorkdayTraversal.Observation observation,
            String extractorVersion) {
        var detail = observation.detail();
        String id = bounded(detail.opaqueId(), 255, "identity");
        String title = bounded(detail.title(), 255, "title");
        String location = detail.location() == null ? observation.search().locationsText() : detail.location();
        if (location != null) location = bounded(location, 255, "location");
        String description = DescriptionTextNormalizer.htmlText(detail.descriptionHtml());
        if (description == null || description.length() > 500_000)
            throw new IllegalArgumentException("description is invalid");
        String url = bounded(detail.externalUrl().toString(), 2048, "externalUrl");
        return new MappedExternalJobListing(id, title, source.companyName(), location, url, url,
                description, digests.calculateWorkday(title, location, description, url, url),
                fingerprints.calculate(extractorVersion, description), JobSourceProvider.WORKDAY);
    }

    private static String bounded(String value, int maximum, String name) {
        if (value == null || value.isBlank() || value.length() > maximum
                || value.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException(name + " is invalid");
        return value.strip();
    }
}
