package com.jobcopilot.discovery.lever;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Immutable provider data; final Job mapping and content assembly belong to Phase 3. */
public record LeverPosting(
        String externalId,
        String title,
        URI hostedUrl,
        URI applyUrl,
        Categories categories,
        String country,
        String workplaceType,
        Instant providerCreatedAt,
        Content content) {

    public LeverPosting {
        Objects.requireNonNull(externalId);
        Objects.requireNonNull(title);
        Objects.requireNonNull(hostedUrl);
        Objects.requireNonNull(applyUrl);
        Objects.requireNonNull(categories);
        Objects.requireNonNull(content);
    }

    public record Categories(
            String location,
            List<String> allLocations,
            String commitment,
            String team,
            String department) {
        public Categories {
            allLocations = List.copyOf(Objects.requireNonNull(allLocations));
        }
    }

    public record Content(
            String descriptionHtml,
            String descriptionPlain,
            List<Section> sections,
            String additionalHtml,
            String additionalPlain) {
        public Content {
            sections = List.copyOf(Objects.requireNonNull(sections));
        }
    }

    public record Section(String heading, String html) {
        public Section {
            Objects.requireNonNull(heading);
            Objects.requireNonNull(html);
        }
    }
}
