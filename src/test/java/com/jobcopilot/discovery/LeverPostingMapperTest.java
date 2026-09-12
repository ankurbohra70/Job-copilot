package com.jobcopilot.discovery;

import com.jobcopilot.discovery.lever.LeverPosting;
import com.jobcopilot.discovery.lever.LeverRegion;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LeverPostingMapperTest {
    private final LeverPostingMapper mapper = new LeverPostingMapper(new LeverDescriptionAssembler(),
            new ProviderContentDigest(), new ExtractionFingerprint());
    private final JobSource source = new JobSource(JobSourceProvider.LEVER, LeverRegion.GLOBAL,
            "example", "Example Company", true);

    @Test void mapsEveryPersistedProjectionAndPrefersPrimaryLocation() {
        LeverMappedListing mapped = mapper.map(source, posting("id-1", "  Senior\nEngineer  ",
                new LeverPosting.Categories(" Remote ", List.of("Delhi", "Pune"), null, null, null),
                new LeverPosting.Content(null, "Required: Java", List.of(), null, null)), "jc005-v1");
        assertEquals("id-1", mapped.externalJobId());
        assertEquals("Senior Engineer", mapped.title());
        assertEquals("Example Company", mapped.company());
        assertEquals("Remote", mapped.location());
        assertEquals("https://jobs.example/id-1", mapped.hostedJobUrl());
        assertEquals("Required: Java", mapped.description());
        assertNotNull(mapped.providerContentDigest());
        assertTrue(mapped.extractionFingerprint().startsWith("jc005-v1:sha256:"));
    }

    @Test void fallsBackToDistinctOrderedAllLocationsAndAllowsBlankDescription() {
        LeverMappedListing mapped = mapper.map(source, posting("id-2", "Engineer",
                new LeverPosting.Categories(null, List.of(" Pune ", "Pune", "Delhi"), null, null, null),
                new LeverPosting.Content(null, null, List.of(), null, null)), "jc005-v1");
        assertEquals("Pune / Delhi", mapped.location());
        assertNull(mapped.description());
        assertNull(mapped.extractionFingerprint());
    }

    @Test void rejectsInvalidOrOverlengthCanonicalText() {
        assertThrows(IllegalArgumentException.class, () -> mapper.map(source, posting("id", "bad\u0000title",
                categories(), content()), "jc005-v1"));
        assertThrows(IllegalArgumentException.class, () -> mapper.map(source, posting("id", "x".repeat(256),
                categories(), content()), "jc005-v1"));
    }

    static LeverPosting posting(String id, String title, LeverPosting.Categories categories,
            LeverPosting.Content content) {
        return new LeverPosting(id, title, URI.create("https://jobs.example/" + id),
                URI.create("https://jobs.example/" + id + "/apply"), categories, null, null, null, content);
    }

    static LeverPosting.Categories categories() {
        return new LeverPosting.Categories(null, List.of(), null, null, null);
    }

    static LeverPosting.Content content() {
        return new LeverPosting.Content(null, "Required: Java", List.of(), null, null);
    }
}
