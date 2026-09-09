package com.jobcopilot.intelligence;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.ArrayNode;
import static com.jobcopilot.intelligence.JobIntelligenceTestSupport.*;
import static com.jobcopilot.intelligence.JobIntelligenceResult.Code;
import static org.junit.jupiter.api.Assertions.*;

class JobIntelligenceSchemaQaTest {
    private final JsonMapper json = JsonMapper.builder().build();
    private final JobIntelligenceDecoder decoder = new JobIntelligenceDecoder();
    private JsonNode schema;

    @Test void everySchemaFieldTypeEnumNullAndBoundIsEnforced() throws Exception {
        try (var stream = getClass().getResourceAsStream("/job-intelligence/schemas/v1/model-output.schema.json")) {
            schema = json.readTree(stream);
        }
        var root = sample(schema);
        success(root);
        audit(schema, root, root, "root");
        var unitEnum = schema.path("$defs").path("ExperienceClause").path("properties").path("unit").path("enum");
        assertEquals(List.of("YEARS", "MONTHS"), java.util.stream.StreamSupport.stream(unitEnum.spliterator(), false)
                .filter(n -> !n.isNull()).map(JsonNode::asString).toList());
        assertEquals(List.of("YEARS", "MONTHS"), java.util.Arrays.stream(JobIntelligence.Unit.values()).map(Enum::name).toList());
    }

    private JsonNode resolve(JsonNode spec) {
        if (spec.has("$ref")) return schema.at(spec.get("$ref").asString().substring(1));
        if (spec.has("anyOf")) return resolve(spec.get("anyOf").get(0));
        return spec;
    }
    private boolean nullable(JsonNode spec) {
        if (spec.has("anyOf")) return true;
        return spec.path("type").isArray() && spec.get("type").toString().contains("null");
    }
    private JsonNode sample(JsonNode original) {
        JsonNode spec = resolve(original);
        if (spec.has("enum")) return spec.get("enum").get(0).deepCopy();
        String type = spec.path("type").isArray() ? spec.get("type").get(0).asString() : spec.path("type").asString();
        return switch (type) {
            case "object" -> {
                ObjectNode node = json.createObjectNode();
                for (String field : spec.get("properties").propertyNames()) node.set(field, sample(spec.get("properties").get(field)));
                yield node;
            }
            case "array" -> json.createArrayNode().add(sample(spec.get("items")));
            case "number" -> json.readTree("1");
            case "boolean" -> json.readTree("false");
            default -> json.readTree("\"x\"");
        };
    }
    private void audit(JsonNode original, JsonNode node, JsonNode root, String path) {
        JsonNode spec = resolve(original);
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            for (String name : new ArrayList<>(object.propertyNames())) {
                JsonNode childSpec = spec.get("properties").get(name);
                JsonNode saved = object.get(name);
                object.remove(name); failure(root, Code.MISSING_FIELD);
                object.putNull(name);
                if (nullable(childSpec)) success(root); else failure(root, Code.NULL_NOT_ALLOWED);
                object.set(name, saved);
                object.set(name, json.createObjectNode());
                if (!saved.isObject()) failure(root, Code.TYPE_MISMATCH);
                object.set(name, saved);
                JsonNode resolved = resolve(childSpec);
                if (resolved.has("enum")) {
                    for (JsonNode value : resolved.get("enum")) { object.set(name, value); success(root); }
                    object.put(name, "invalid"); failure(root, Code.INVALID_ENUM);
                    object.put(name, 0); failure(root, Code.TYPE_MISMATCH);
                    object.set(name, saved);
                } else if (saved.isString()) {
                    int max = resolved.get("maxLength").asInt();
                    object.put(name, "x".repeat(max)); success(root);
                    object.put(name, "x".repeat(max + 1)); failure(root, Code.BOUND_VIOLATION);
                    object.put(name, ""); failure(root, Code.BOUND_VIOLATION);
                    object.set(name, saved);
                } else if (saved.isNumber()) {
                    object.put(name, 0); success(root);
                    object.put(name, 960); success(root);
                    object.put(name, -1); failure(root, Code.BOUND_VIOLATION);
                    object.put(name, 961); failure(root, Code.BOUND_VIOLATION);
                    object.put(name, "1"); failure(root, Code.TYPE_MISMATCH);
                    object.set(name, saved);
                } else if (saved.isBoolean()) {
                    object.put(name, "false"); failure(root, Code.TYPE_MISMATCH);
                    object.set(name, saved);
                }
                audit(childSpec, saved, root, path + "." + name);
            }
            object.put("UNKNOWN_SECRET", 1); failure(root, Code.UNKNOWN_FIELD); object.remove("UNKNOWN_SECRET");
        } else if (node.isArray()) {
            ArrayNode array = (ArrayNode) node;
            JsonNode item = array.get(0);
            audit(spec.get("items"), item, root, path + "[0]");
            array.removeAll();
            int min = spec.path("minItems").asInt(0);
            if (min == 0) success(root); else failure(root, Code.BOUND_VIOLATION);
            array.addNull(); failure(root, Code.NULL_COLLECTION_ELEMENT); array.removeAll();
            int max = spec.has("maxItems") ? spec.get("maxItems").asInt() : 64;
            for (int i = 0; i < max; i++) array.add(item.deepCopy());
            success(root);
            array.add(item.deepCopy());
            failure(root, spec.has("maxItems") ? Code.BOUND_VIOLATION : Code.PROCESSING_VOLUME_EXCEEDED);
            array.removeAll(); array.add(item);
        }
    }
    @Test void resourceLimitsAndStrictSyntax() {
        failure("{" + " ".repeat(262144) + "}", Code.CANDIDATE_TOO_LARGE);
        failure("{\"x\":" + "[".repeat(16) + "0" + "]".repeat(16) + "}", Code.NESTING_TOO_DEEP);
        failure("{\"x\":" + "[".repeat(15) + "0" + "]".repeat(15) + "}", Code.UNKNOWN_FIELD);
        failure("{\"x\":" + "1".repeat(33) + "}", Code.NUMERIC_TOKEN_TOO_LONG);
        failure("{\"x\":[" + "0,".repeat(4096) + "0]}", Code.PROCESSING_VOLUME_EXCEEDED);
        for (String suffix : List.of(" duplicate", " trailing", " {}", " []", " true", " /*x*/")) failure(javaRequired() + suffix, Code.TRAILING_CONTENT);
        failure("{\"facts\":{\"skills\":[{\"name\":\"a\",\"name\":\"b\"}]}}", Code.DUPLICATE_FIELD);
        failure("{\"duplicate\": trailing}", Code.MALFORMED_JSON);
        failure("{\"x\": /* comment */ 1}", Code.MALFORMED_JSON);
        failure("```json\n" + javaRequired(), Code.NOT_SINGLE_OBJECT);
        failure("prefix " + javaRequired(), Code.NOT_SINGLE_OBJECT);
        assertInstanceOf(JobIntelligenceDecoder.Result.Success.class, decoder.decode(withExperience("1 year", "1e0", "YEARS", "REQUIRED", "OVERALL", false, "1 year")));
        // Huge exponent must be bounded by the parser/decimal conversion, with no expanded allocation.
        assertInstanceOf(JobIntelligenceDecoder.Result.Failure.class, decoder.decode(withExperience("1 year", "1e9999999999", "YEARS", "REQUIRED", "OVERALL", false, "1 year")));
    }
    private void success(JsonNode root) { assertInstanceOf(JobIntelligenceDecoder.Result.Success.class, decoder.decode(root.toString()), root.toString()); }
    private void failure(JsonNode root, Code code) { failure(root.toString(), code); }
    private void failure(String value, Code code) {
        var failure = assertInstanceOf(JobIntelligenceDecoder.Result.Failure.class, decoder.decode(value));
        assertEquals(code, failure.code());
        assertFalse(failure.location().path().contains("SECRET"));
    }
}
