package com.jobcopilot.discovery.lever;

import java.net.URI;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LeverEndpointResolverTest {
    private final LeverEndpointResolver resolver = new LeverEndpointResolver();

    @Test void resolvesExactProductionRegionEndpoints() {
        URI global = resolver.resolve(new LeverSource(LeverRegion.GLOBAL, "example-site"),
                new LeverPageRequest(0, 100));
        URI eu = resolver.resolve(new LeverSource(LeverRegion.EU, "example-site"),
                new LeverPageRequest(25, 50));

        assertThat(global.toASCIIString()).isEqualTo(
                "https://api.lever.co/v0/postings/example-site?mode=json&skip=0&limit=100");
        assertThat(eu.toASCIIString()).isEqualTo(
                "https://api.eu.lever.co/v0/postings/example-site?mode=json&skip=25&limit=50");
    }

    @Test void encodesSourceAsExactlyOnePathSegmentWithoutInjection() {
        URI uri = resolver.resolve(new LeverSource(LeverRegion.GLOBAL, "site /?#%café"),
                new LeverPageRequest(2, 3));

        assertThat(uri.getHost()).isEqualTo("api.lever.co");
        assertThat(uri.getRawPath()).isEqualTo("/v0/postings/site%20%2F%3F%23%25caf%C3%A9");
        assertThat(uri.getRawQuery()).isEqualTo("mode=json&skip=2&limit=3");
        assertThat(uri.getFragment()).isNull();
        assertThat(uri.getUserInfo()).isNull();
    }

    @Test void doesNotNormalizeSourceOrAcceptUnsafeBases() {
        assertThatThrownBy(() -> new LeverSource(LeverRegion.GLOBAL, "UPPER"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LeverEndpointResolver(
                URI.create("https://user@example.test/v0/postings/"),
                URI.create("https://example.test/v0/postings/"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LeverEndpointResolver(
                URI.create("https://example.test/other/"), URI.create("https://example.test/v0/postings/")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
