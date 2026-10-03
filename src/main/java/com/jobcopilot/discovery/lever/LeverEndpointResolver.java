package com.jobcopilot.discovery.lever;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

final class LeverEndpointResolver {
    private static final URI GLOBAL_BASE = URI.create("https://api.lever.co/v0/postings/");
    private static final URI EU_BASE = URI.create("https://api.eu.lever.co/v0/postings/");
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private final URI globalBase;
    private final URI euBase;

    LeverEndpointResolver() {
        this(GLOBAL_BASE, EU_BASE);
    }

    LeverEndpointResolver(URI globalBase, URI euBase) {
        this.globalBase = validBase(globalBase);
        this.euBase = validBase(euBase);
    }

    URI resolve(LeverSource source, LeverPageRequest page) {
        Objects.requireNonNull(source);
        Objects.requireNonNull(page);
        URI base = source.region() == LeverRegion.GLOBAL ? globalBase : euBase;
        return URI.create(base.toASCIIString() + encodePathSegment(source.sourceKey())
                + "?mode=json&skip=" + page.skip() + "&limit=" + page.limit());
    }

    private static URI validBase(URI value) {
        Objects.requireNonNull(value, "base URI is required");
        String scheme = value.getScheme();
        if (!value.isAbsolute() || value.getHost() == null
                || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                || value.getRawUserInfo() != null || value.getRawQuery() != null || value.getRawFragment() != null
                || !value.getRawPath().endsWith("/v0/postings/")) {
            throw new IllegalArgumentException("base URI must end with /v0/postings/");
        }
        return value;
    }

    private static String encodePathSegment(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        StringBuilder encoded = new StringBuilder(bytes.length);
        for (byte item : bytes) {
            int character = item & 0xff;
            if ((character >= 'a' && character <= 'z') || (character >= 'A' && character <= 'Z')
                    || (character >= '0' && character <= '9') || character == '-'
                    || character == '.' || character == '_' || character == '~') {
                encoded.append((char) character);
            } else {
                encoded.append('%').append(HEX[character >>> 4]).append(HEX[character & 0xf]);
            }
        }
        return encoded.toString();
    }
}
