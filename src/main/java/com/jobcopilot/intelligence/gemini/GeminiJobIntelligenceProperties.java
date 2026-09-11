package com.jobcopilot.intelligence.gemini;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("job-intelligence.gemini")
public final class GeminiJobIntelligenceProperties {
    private final boolean enabled;
    private final String apiKey;
    private final String baseUrl;
    private final Duration connectTimeout;

    public GeminiJobIntelligenceProperties(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("") String apiKey,
            @DefaultValue("https://generativelanguage.googleapis.com") String baseUrl,
            @DefaultValue("5s") Duration connectTimeout) {
        this.enabled = enabled;
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.connectTimeout = connectTimeout;
    }

    public boolean enabled() { return enabled; }
    public String apiKey() { return apiKey; }
    public String baseUrl() { return baseUrl; }
    public Duration connectTimeout() { return connectTimeout; }

    URI validatedBaseUrl() {
        if (apiKey == null || apiKey.isBlank())
            throw new IllegalStateException("job-intelligence.gemini.api-key must be configured when Gemini is enabled");
        if (connectTimeout == null || connectTimeout.isZero() || connectTimeout.isNegative())
            throw new IllegalStateException("job-intelligence.gemini.connect-timeout must be positive when Gemini is enabled");
        try {
            URI uri = URI.create(baseUrl == null ? "" : baseUrl);
            if (!uri.isAbsolute() || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                    || uri.getRawFragment() != null)
                throw new IllegalArgumentException();
            return uri;
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException(
                    "job-intelligence.gemini.base-url must be an absolute HTTP(S) URL when Gemini is enabled");
        }
    }

    @Override public String toString() {
        return "GeminiJobIntelligenceProperties[enabled=" + enabled + ", apiKey=<redacted>, baseUrl="
                + baseUrl + ", connectTimeout=" + connectTimeout + "]";
    }
}
