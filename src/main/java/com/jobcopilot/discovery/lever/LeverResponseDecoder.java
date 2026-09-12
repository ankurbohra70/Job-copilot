package com.jobcopilot.discovery.lever;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

final class LeverResponseDecoder {
    private static final int EXTERNAL_ID_MAX_LENGTH = 255;
    private static final int URL_MAX_LENGTH = 2048;
    private final JsonMapper json = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();

    DecodeResult decode(byte[] body) {
        return decode(new ByteArrayInputStream(body));
    }

    DecodeResult decode(InputStream body) {
        try {
            JsonNode root = json.readTree(body);
            if (root == null || root.isMissingNode()) return new DecodeResult.Malformed();
            if (!root.isArray()) return new DecodeResult.Invalid();
            List<LeverPosting> postings = new ArrayList<>(root.size());
            Set<String> ids = new HashSet<>();
            for (JsonNode value : root) {
                LeverPosting posting = posting(value);
                if (!ids.add(posting.externalId())) throw new InvalidPayload();
                postings.add(posting);
            }
            return new DecodeResult.Decoded(postings);
        } catch (InvalidPayload invalid) {
            return new DecodeResult.Invalid();
        } catch (RuntimeException malformed) {
            return new DecodeResult.Malformed();
        }
    }

    private static LeverPosting posting(JsonNode node) {
        if (node == null || !node.isObject()) throw new InvalidPayload();
        String externalId = canonicalExternalId(requiredText(node, "id"));
        String title = requiredText(node, "text");
        if (title.isBlank()) throw new InvalidPayload();
        URI hostedUrl = requiredHttpsUrl(node, "hostedUrl");
        URI applyUrl = requiredHttpsUrl(node, "applyUrl");
        LeverPosting.Categories categories = categories(node.get("categories"));
        String country = optionalText(node, "country");
        String workplaceType = optionalText(node, "workplaceType");
        Instant createdAt = optionalCreatedAt(node.get("createdAt"));
        LeverPosting.Content content = new LeverPosting.Content(
                optionalText(node, "description"), optionalText(node, "descriptionPlain"),
                sections(node.get("lists")), optionalText(node, "additional"),
                optionalText(node, "additionalPlain"));
        return new LeverPosting(externalId, title, hostedUrl, applyUrl, categories,
                country, workplaceType, createdAt, content);
    }

    private static LeverPosting.Categories categories(JsonNode node) {
        if (node == null || node.isNull()) return new LeverPosting.Categories(null, List.of(), null, null, null);
        if (!node.isObject()) throw new InvalidPayload();
        return new LeverPosting.Categories(optionalText(node, "location"),
                optionalTextArray(node.get("allLocations")), optionalText(node, "commitment"),
                optionalText(node, "team"), optionalText(node, "department"));
    }

    private static List<LeverPosting.Section> sections(JsonNode node) {
        if (node == null || node.isNull()) return List.of();
        if (!node.isArray()) throw new InvalidPayload();
        List<LeverPosting.Section> sections = new ArrayList<>(node.size());
        for (JsonNode value : node) {
            if (value == null || !value.isObject()) throw new InvalidPayload();
            sections.add(new LeverPosting.Section(requiredText(value, "text"), requiredText(value, "content")));
        }
        return sections;
    }

    private static List<String> optionalTextArray(JsonNode node) {
        if (node == null || node.isNull()) return List.of();
        if (!node.isArray()) throw new InvalidPayload();
        List<String> values = new ArrayList<>(node.size());
        for (JsonNode value : node) {
            if (!value.isTextual()) throw new InvalidPayload();
            values.add(value.asText());
        }
        return values;
    }

    private static String canonicalExternalId(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) == ' ') start++;
        while (end > start && value.charAt(end - 1) == ' ') end--;
        String canonical = value.substring(start, end);
        if (canonical.isEmpty() || canonical.length() > EXTERNAL_ID_MAX_LENGTH
                || canonical.chars().anyMatch(Character::isISOControl)) throw new InvalidPayload();
        return canonical;
    }

    private static String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) throw new InvalidPayload();
        return value.asText();
    }

    private static String optionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual()) throw new InvalidPayload();
        return value.asText();
    }

    private static URI requiredHttpsUrl(JsonNode node, String field) {
        String raw = requiredText(node, field).strip();
        if (raw.isEmpty() || raw.length() > URL_MAX_LENGTH) throw new InvalidPayload();
        try {
            URI uri = URI.create(raw);
            if (!uri.isAbsolute() || !"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getRawUserInfo() != null) throw new InvalidPayload();
            return uri;
        } catch (IllegalArgumentException invalid) {
            throw new InvalidPayload();
        }
    }

    private static Instant optionalCreatedAt(JsonNode node) {
        if (node == null || node.isNull() || !node.isIntegralNumber() || !node.canConvertToLong()) return null;
        long epochMillis = node.longValue();
        if (epochMillis < 0) return null;
        try {
            return Instant.ofEpochMilli(epochMillis);
        } catch (DateTimeException invalid) {
            return null;
        }
    }

    sealed interface DecodeResult {
        record Decoded(List<LeverPosting> postings) implements DecodeResult {
            public Decoded { postings = List.copyOf(postings); }
        }
        record Malformed() implements DecodeResult {}
        record Invalid() implements DecodeResult {}
    }

    private static final class InvalidPayload extends RuntimeException {}
}
