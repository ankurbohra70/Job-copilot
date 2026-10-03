package com.jobcopilot.discovery.workday;

import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

final class WorkdayResponseDecoder {
    static final int MAX_STRING_LENGTH = 500_000;
    private static final int MAX_ID_LENGTH = 255;
    private static final int MAX_TITLE_LENGTH = 2_000;
    private static final int MAX_URL_LENGTH = 2_048;
    private static final int MAX_BULLET_FIELDS = 50;
    private final JsonMapper json = JsonMapper.builder(JsonFactory.builder()
                    .streamReadConstraints(StreamReadConstraints.builder()
                            .maxNestingDepth(32).maxStringLength(MAX_STRING_LENGTH)
                            .maxNameLength(256).maxNumberLength(32).maxTokenCount(200_000).build())
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    SearchDecodeResult decodeSearch(InputStream body, WorkdayPageRequest request) {
        try {
            JsonNode root = json.readTree(body);
            if (root == null || !root.isObject()) throw new InvalidPayload();
            JsonNode totalNode = root.get("total");
            if (totalNode == null && request.offset() == 0) throw new InvalidPayload();
            if (totalNode != null && (!totalNode.isIntegralNumber() || !totalNode.canConvertToInt()))
                throw new InvalidPayload();
            Integer total = totalNode == null ? null : totalNode.intValue();
            if (total != null && total < 0) throw new InvalidPayload();
            JsonNode rows = root.get("jobPostings");
            if (rows == null || !rows.isArray() || rows.size() > request.limit()) throw new InvalidPayload();
            List<WorkdaySearchPosting> postings = new ArrayList<>(rows.size());
            Set<String> paths = new HashSet<>();
            for (JsonNode row : rows) {
                if (row == null || !row.isObject()) throw new InvalidPayload();
                if (isNonPostingMarker(row)) {
                    optionalTextArray(row.get("bulletFields"));
                    continue;
                }
                String title = requiredText(row, "title", MAX_TITLE_LENGTH, false);
                String path = requiredText(row, "externalPath", MAX_URL_LENGTH, false);
                new WorkdayDetailRequest(path);
                if (!paths.add(path)) throw new InvalidPayload();
                postings.add(new WorkdaySearchPosting(title, path,
                        optionalText(row, "locationsText", MAX_TITLE_LENGTH),
                        optionalText(row, "postedOn", MAX_TITLE_LENGTH),
                        optionalText(row, "remoteType", MAX_TITLE_LENGTH),
                        optionalTextArray(row.get("bulletFields"))));
            }
            return new SearchDecodeResult.Decoded(total, postings, rows.size());
        } catch (InvalidPayload | IllegalArgumentException invalid) {
            return new SearchDecodeResult.Invalid();
        } catch (RuntimeException malformed) {
            return new SearchDecodeResult.Malformed();
        }
    }

    DetailDecodeResult decodeDetail(InputStream body, WorkdaySource source) {
        try {
            JsonNode root = json.readTree(body);
            if (root == null || !root.isObject()) throw new InvalidPayload();
            JsonNode info = root.get("jobPostingInfo");
            if (info == null || !info.isObject()) throw new InvalidPayload();
            String id = requiredText(info, "id", MAX_ID_LENGTH, true);
            String title = requiredText(info, "title", MAX_TITLE_LENGTH, false);
            String description = requiredText(info, "jobDescription", MAX_STRING_LENGTH, false);
            URI externalUrl = externalUrl(info, source);
            boolean posted = requiredBoolean(info, "posted");
            boolean canApply = requiredBoolean(info, "canApply");
            String siteId = optionalText(info, "jobPostingSiteId", 100);
            if (siteId != null && !siteId.equals(source.siteKey())) throw new InvalidPayload();
            WorkdayJobDetail detail = new WorkdayJobDetail(id, title, description,
                    optionalText(info, "location", MAX_TITLE_LENGTH),
                    optionalText(info, "postedOn", MAX_TITLE_LENGTH),
                    optionalText(info, "startDate", 64), optionalText(info, "timeType", 255),
                    optionalText(info, "remoteType", 255), optionalText(info, "jobReqId", MAX_ID_LENGTH),
                    optionalText(info, "jobPostingId", MAX_ID_LENGTH), siteId,
                    externalUrl, posted, canApply);
            return new DetailDecodeResult.Decoded(detail);
        } catch (InvalidPayload | IllegalArgumentException invalid) {
            return new DetailDecodeResult.Invalid();
        } catch (RuntimeException malformed) {
            return new DetailDecodeResult.Malformed();
        }
    }

    private static URI externalUrl(JsonNode info, WorkdaySource source) {
        String value = requiredText(info, "externalUrl", MAX_URL_LENGTH, true);
        URI uri = URI.create(value);
        if (!uri.isAbsolute() || uri.isOpaque() || !"https".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getPort() != -1
                || uri.getRawFragment() != null
                || !uri.getHost().toLowerCase(Locale.ROOT).equals(source.endpointHost())) {
            throw new InvalidPayload();
        }
        String sitePath = source.endpointHost().endsWith(".myworkdaysite.com")
                ? "/recruiting/" + source.tenantKey() + "/" + source.siteKey() + "/job/"
                : "/" + source.siteKey() + "/job/";
        if (uri.getRawPath() == null || !uri.getRawPath().startsWith(sitePath)) throw new InvalidPayload();
        new WorkdayDetailRequest(uri.getRawPath().substring(sitePath.length() - "/job/".length()));
        return uri;
    }

    private static String requiredText(JsonNode node, String field, int max, boolean noControls) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) throw new InvalidPayload();
        String text = value.asText();
        if (text.isBlank() || text.length() > max || noControls && text.chars().anyMatch(Character::isISOControl))
            throw new InvalidPayload();
        return text;
    }

    private static String optionalText(JsonNode node, String field, int max) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual() || value.asText().length() > max) throw new InvalidPayload();
        return value.asText();
    }

    private static boolean requiredBoolean(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isBoolean()) throw new InvalidPayload();
        return value.booleanValue();
    }

    private static List<String> optionalTextArray(JsonNode node) {
        if (node == null || node.isNull()) return List.of();
        if (!node.isArray() || node.size() > MAX_BULLET_FIELDS) throw new InvalidPayload();
        List<String> values = new ArrayList<>(node.size());
        for (JsonNode item : node) {
            if (!item.isTextual() || item.asText().length() > MAX_TITLE_LENGTH) throw new InvalidPayload();
            values.add(item.asText());
        }
        return values;
    }

    private static boolean isNonPostingMarker(JsonNode row) {
        return row.size() == 1 && row.has("bulletFields") && row.get("bulletFields").isArray()
                && !row.get("bulletFields").isEmpty();
    }

    sealed interface SearchDecodeResult {
        record Decoded(Integer reportedTotal, List<WorkdaySearchPosting> postings, int returnedRowCount)
                implements SearchDecodeResult {
            public Decoded { postings = List.copyOf(postings); }
        }
        record Malformed() implements SearchDecodeResult {}
        record Invalid() implements SearchDecodeResult {}
    }
    sealed interface DetailDecodeResult {
        record Decoded(WorkdayJobDetail detail) implements DetailDecodeResult {}
        record Malformed() implements DetailDecodeResult {}
        record Invalid() implements DetailDecodeResult {}
    }
    private static final class InvalidPayload extends RuntimeException {}
}
