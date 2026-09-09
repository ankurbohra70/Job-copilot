package com.jobcopilot.intelligence;

import java.math.BigDecimal;
import java.util.Set;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.exc.StreamReadException;
import tools.jackson.core.json.JsonReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import static com.jobcopilot.intelligence.JobIntelligenceResult.Code;
import static com.jobcopilot.intelligence.JobIntelligenceResult.Location;

/**
 * Strict local candidate-JSON decoder. Operational limits are runtime safety, not schema identity.
 * Limits: 262144 chars, depth 16, numeric token 32 chars, 64 uncertainties, 4096 nodes.
 */
final class JobIntelligenceDecoder {
    static final int MAX_CANDIDATE_CHARS = 262144;
    static final int MAX_NESTING_DEPTH = 16;
    static final int MAX_NUMERIC_TOKEN_LENGTH = 32;
    static final int MAX_UNCERTAINTIES = 64;
    static final int MAX_NODES = 4096;
    static final String POLICY_NOTE = "runtime operational limits, not frozen v1 schema constraints";

    private final JsonMapper mapper = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .disable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .disable(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
            .disable(DeserializationFeature.UNWRAP_SINGLE_VALUE_ARRAYS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT)
            .disable(DeserializationFeature.ACCEPT_EMPTY_ARRAY_AS_NULL_OBJECT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .disable(MapperFeature.APPLY_DEFAULT_VALUES)
            .disable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
            .disable(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
            .disable(MapperFeature.ACCEPT_CASE_INSENSITIVE_VALUES)
            .disable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
            .disable(JsonReadFeature.ALLOW_YAML_COMMENTS)
            .disable(JsonReadFeature.ALLOW_SINGLE_QUOTES)
            .disable(JsonReadFeature.ALLOW_UNQUOTED_PROPERTY_NAMES)
            .disable(JsonReadFeature.ALLOW_LEADING_ZEROS_FOR_NUMBERS)
            .disable(JsonReadFeature.ALLOW_TRAILING_COMMA)
            .disable(StreamReadFeature.USE_FAST_DOUBLE_PARSER)
            .disable(StreamReadFeature.USE_FAST_BIG_NUMBER_PARSER)
            .disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)
            .build();

    sealed interface Result {
        record Success(JobIntelligenceModel.Output output) implements Result {}
        record Failure(Code code, Location location) implements Result {}
    }

    Result decode(String candidateJson) {
        if (candidateJson == null || candidateJson.isEmpty()) {
            return new Result.Failure(Code.EMPTY_CANDIDATE, Location.root());
        }
        if (candidateJson.length() > MAX_CANDIDATE_CHARS) {
            return new Result.Failure(Code.CANDIDATE_TOO_LARGE, Location.root());
        }
        int first = firstNonWhitespace(candidateJson);
        if (first < 0 || candidateJson.charAt(first) != '{') {
            return new Result.Failure(Code.NOT_SINGLE_OBJECT, Location.root());
        }
        Result.Failure scanned = scan(candidateJson);
        if (scanned != null) return scanned;
        JsonNode tree;
        try {
            tree = mapper.readTree(candidateJson);
        } catch (RuntimeException exception) {
            return classifyParse(exception);
        }
        if (tree == null || !tree.isObject()) return new Result.Failure(Code.NOT_SINGLE_OBJECT, Location.root());
        Result checked = validateTree(tree);
        if (checked instanceof Result.Failure failure) return failure;
        try {
            return new Result.Success(mapper.treeToValue(tree, JobIntelligenceModel.Output.class));
        } catch (RuntimeException exception) {
            return classifyParse(exception);
        }
    }

    private Result.Failure scan(String json) {
        try (JsonParser parser = mapper.createParser(json)) {
            int depth = 0;
            int nodes = 0;
            JsonToken root = parser.nextToken();
            if (root != JsonToken.START_OBJECT) return new Result.Failure(Code.NOT_SINGLE_OBJECT, Location.root());
            depth = 1;
            nodes = 1;
            JsonToken token;
            while ((token = parser.nextToken()) != null) {
                if (depth == 0) return new Result.Failure(Code.TRAILING_CONTENT, Location.root());
                if (token == JsonToken.START_OBJECT || token == JsonToken.START_ARRAY) {
                    depth++;
                    if (depth > MAX_NESTING_DEPTH) return new Result.Failure(Code.NESTING_TOO_DEEP, Location.root());
                } else if (token == JsonToken.END_OBJECT || token == JsonToken.END_ARRAY) {
                    depth--;
                }
                if (token == JsonToken.VALUE_NUMBER_INT || token == JsonToken.VALUE_NUMBER_FLOAT) {
                    String text = parser.getText();
                    if (text != null && text.length() > MAX_NUMERIC_TOKEN_LENGTH) {
                        return new Result.Failure(Code.NUMERIC_TOKEN_TOO_LONG, Location.root());
                    }
                }
                nodes++;
                if (nodes > MAX_NODES) return new Result.Failure(Code.PROCESSING_VOLUME_EXCEEDED, Location.root());
                if (depth == 0) {
                    // Inspect remaining bytes before asking Jackson to tokenize arbitrary trailing prose.
                    int end = (int) parser.currentLocation().getCharOffset();
                    for (int i = end; i < json.length(); i++) {
                        char c = json.charAt(i);
                        if (c != ' ' && c != '\t' && c != '\r' && c != '\n')
                            return new Result.Failure(Code.TRAILING_CONTENT, Location.root());
                    }
                    return null;
                }
            }
            return null;
        } catch (RuntimeException exception) {
            Result classified = classifyParse(exception);
            return classified instanceof Result.Failure failure ? failure : new Result.Failure(Code.MALFORMED_JSON, Location.root());
        }
    }

    private Result validateTree(JsonNode tree) {
        ObjectNode root = (ObjectNode) tree;
        Result fields = objectFields(root, "", Set.of("facts", "interpretations", "evidence", "uncertainties"));
        if (fields instanceof Result.Failure failure) return failure;
        Result facts = facts(root.get("facts"), "facts");
        if (facts instanceof Result.Failure failure) return facts;
        Result interpretations = interpretations(root.get("interpretations"), "interpretations");
        if (interpretations instanceof Result.Failure failure) return interpretations;
        Result evidence = array(root.get("evidence"), "evidence", 80, this::evidence);
        if (evidence instanceof Result.Failure failure) return evidence;
        JsonNode uncertainties = root.get("uncertainties");
        if (uncertainties == null) return new Result.Failure(Code.MISSING_FIELD, Location.of("uncertainties"));
        if (uncertainties.isNull()) return new Result.Failure(Code.NULL_NOT_ALLOWED, Location.of("uncertainties"));
        if (!uncertainties.isArray()) return new Result.Failure(Code.TYPE_MISMATCH, Location.of("uncertainties"));
        if (uncertainties.size() > MAX_UNCERTAINTIES) return new Result.Failure(Code.PROCESSING_VOLUME_EXCEEDED, Location.of("uncertainties"));
        return array(uncertainties, "uncertainties", MAX_UNCERTAINTIES, this::uncertainty);
    }

    private Result facts(JsonNode node, String path) {
        Result object = requireObject(node, path, Set.of("skills", "experienceClauses", "qualifications"));
        if (object instanceof Result.Failure failure) return failure;
        ObjectNode facts = (ObjectNode) node;
        Result skills = array(facts.get("skills"), path + ".skills", 40, this::skill);
        if (skills instanceof Result.Failure failure) return skills;
        Result clauses = array(facts.get("experienceClauses"), path + ".experienceClauses", 8, this::experienceClause);
        if (clauses instanceof Result.Failure failure) return clauses;
        return array(facts.get("qualifications"), path + ".qualifications", 12, this::qualification);
    }

    private Result interpretations(JsonNode node, String path) {
        Result object = requireObject(node, path, Set.of("roleFamily", "seniority", "responsibilities", "technicalConcepts"));
        if (object instanceof Result.Failure failure) return failure;
        ObjectNode interpretations = (ObjectNode) node;
        Result role = optionalClaim(interpretations.get("roleFamily"), path + ".roleFamily", Set.of("value"), JobIntelligence.Role.class);
        if (role instanceof Result.Failure failure) return role;
        Result seniority = optionalClaim(interpretations.get("seniority"), path + ".seniority", Set.of("value"), JobIntelligence.Seniority.class);
        if (seniority instanceof Result.Failure failure) return seniority;
        Result responsibilities = array(interpretations.get("responsibilities"), path + ".responsibilities", 12, this::textClaim);
        if (responsibilities instanceof Result.Failure failure) return responsibilities;
        return array(interpretations.get("technicalConcepts"), path + ".technicalConcepts", 12, this::textClaim);
    }

    private Result skill(JsonNode node, String path) {
        Result object = requireObject(node, path, Set.of("name", "importance", "evidenceIds"));
        if (object instanceof Result.Failure failure) return failure;
        ObjectNode skill = (ObjectNode) node;
        Result name = requiredString(skill.get("name"), path + ".name", 300);
        if (name instanceof Result.Failure failure) return name;
        Result importance = requiredEnum(skill.get("importance"), path + ".importance", JobIntelligence.Importance.class);
        if (importance instanceof Result.Failure failure) return importance;
        return evidenceIds(skill.get("evidenceIds"), path + ".evidenceIds", 1);
    }

    private Result experienceClause(JsonNode node, String path) {
        Result object = requireObject(node, path, Set.of("text", "minimum", "unit", "importance", "scope", "conditional", "evidenceIds"));
        if (object instanceof Result.Failure failure) return failure;
        ObjectNode clause = (ObjectNode) node;
        Result text = requiredString(clause.get("text"), path + ".text", 300);
        if (text instanceof Result.Failure failure) return text;
        Result minimum = optionalNumber(clause.get("minimum"), path + ".minimum", new BigDecimal("0"), new BigDecimal("960"));
        if (minimum instanceof Result.Failure failure) return minimum;
        Result unit = optionalEnum(clause.get("unit"), path + ".unit", JobIntelligence.Unit.class);
        if (unit instanceof Result.Failure failure) return unit;
        Result importance = requiredEnum(clause.get("importance"), path + ".importance", JobIntelligence.Importance.class);
        if (importance instanceof Result.Failure failure) return importance;
        Result scope = requiredEnum(clause.get("scope"), path + ".scope", JobIntelligence.Scope.class);
        if (scope instanceof Result.Failure failure) return scope;
        Result conditional = requiredBoolean(clause.get("conditional"), path + ".conditional");
        if (conditional instanceof Result.Failure failure) return conditional;
        return evidenceIds(clause.get("evidenceIds"), path + ".evidenceIds", 1);
    }

    private Result qualification(JsonNode node, String path) {
        Result object = requireObject(node, path, Set.of("text", "importance", "evidenceIds"));
        if (object instanceof Result.Failure failure) return failure;
        ObjectNode qualification = (ObjectNode) node;
        Result text = requiredString(qualification.get("text"), path + ".text", 300);
        if (text instanceof Result.Failure failure) return text;
        Result importance = requiredEnum(qualification.get("importance"), path + ".importance", JobIntelligence.Importance.class);
        if (importance instanceof Result.Failure failure) return importance;
        return evidenceIds(qualification.get("evidenceIds"), path + ".evidenceIds", 1);
    }

    private Result textClaim(JsonNode node, String path) {
        Result object = requireObject(node, path, Set.of("text", "evidenceIds"));
        if (object instanceof Result.Failure failure) return failure;
        ObjectNode claim = (ObjectNode) node;
        Result text = requiredString(claim.get("text"), path + ".text", 300);
        if (text instanceof Result.Failure failure) return text;
        return evidenceIds(claim.get("evidenceIds"), path + ".evidenceIds", 1);
    }

    private Result evidence(JsonNode node, String path) {
        Result object = requireObject(node, path, Set.of("id", "source", "quote"));
        if (object instanceof Result.Failure failure) return failure;
        ObjectNode evidence = (ObjectNode) node;
        Result id = requiredString(evidence.get("id"), path + ".id", 300);
        if (id instanceof Result.Failure failure) return id;
        Result source = requiredEnum(evidence.get("source"), path + ".source", JobIntelligence.Source.class);
        if (source instanceof Result.Failure failure) return source;
        return requiredString(evidence.get("quote"), path + ".quote", 500);
    }

    private Result uncertainty(JsonNode node, String path) {
        Result object = requireObject(node, path, Set.of("code", "target", "evidenceIds"));
        if (object instanceof Result.Failure failure) return failure;
        ObjectNode uncertainty = (ObjectNode) node;
        Result code = requiredEnum(uncertainty.get("code"), path + ".code", JobIntelligence.UncertaintyCode.class);
        if (code instanceof Result.Failure failure) return code;
        Result target = requiredString(uncertainty.get("target"), path + ".target", 300);
        if (target instanceof Result.Failure failure) return target;
        return evidenceIds(uncertainty.get("evidenceIds"), path + ".evidenceIds", 0);
    }

    private Result optionalClaim(JsonNode node, String path, Set<String> extra, Class<? extends Enum<?>> enumType) {
        if (node == null) return new Result.Failure(Code.MISSING_FIELD, Location.of(path));
        if (node.isNull()) return new Result.Success(null);
        Result object = requireObject(node, path, union(extra, Set.of("evidenceIds")));
        if (object instanceof Result.Failure failure) return failure;
        Result value = requiredEnum(node.get("value"), path + ".value", enumType);
        if (value instanceof Result.Failure failure) return value;
        return evidenceIds(node.get("evidenceIds"), path + ".evidenceIds", 1);
    }

    private Result evidenceIds(JsonNode node, String path, int minItems) {
        Result items = array(node, path, 3, (element, elementPath) -> requiredString(element, elementPath, 300));
        if (items instanceof Result.Failure failure) return failure;
        if (node.size() < minItems) return new Result.Failure(Code.BOUND_VIOLATION, Location.of(path));
        return new Result.Success(null);
    }

    private Result array(JsonNode node, String path, int maxItems, ElementValidator validator) {
        if (node == null) return new Result.Failure(Code.MISSING_FIELD, Location.of(path));
        if (node.isNull()) return new Result.Failure(Code.NULL_NOT_ALLOWED, Location.of(path));
        if (!node.isArray()) return new Result.Failure(Code.TYPE_MISMATCH, Location.of(path));
        ArrayNode array = (ArrayNode) node;
        if (array.size() > maxItems) return new Result.Failure(Code.BOUND_VIOLATION, Location.of(path));
        for (int i = 0; i < array.size(); i++) {
            JsonNode element = array.get(i);
            String elementPath = path + "[" + i + "]";
            if (element == null || element.isNull()) return new Result.Failure(Code.NULL_COLLECTION_ELEMENT, Location.of(elementPath));
            Result result = validator.validate(element, elementPath);
            if (result instanceof Result.Failure failure) return failure;
        }
        return new Result.Success(null);
    }

    private Result requireObject(JsonNode node, String path, Set<String> required) {
        if (node == null) return new Result.Failure(Code.MISSING_FIELD, Location.of(path));
        if (node.isNull()) return new Result.Failure(Code.NULL_NOT_ALLOWED, Location.of(path));
        if (!node.isObject()) return new Result.Failure(Code.TYPE_MISMATCH, Location.of(path));
        return objectFields((ObjectNode) node, path, required);
    }

    private Result objectFields(ObjectNode node, String path, Set<String> required) {
        for (String name : node.propertyNames()) {
            if (!required.contains(name)) return new Result.Failure(Code.UNKNOWN_FIELD, Location.of(path));
        }
        for (String name : new java.util.TreeSet<>(required)) {
            if (!node.has(name)) return new Result.Failure(Code.MISSING_FIELD, Location.of(join(path, name)));
        }
        return new Result.Success(null);
    }

    private Result requiredString(JsonNode node, String path, int maxLength) {
        if (node == null || node.isMissingNode()) return new Result.Failure(Code.MISSING_FIELD, Location.of(path));
        if (node.isNull()) return new Result.Failure(Code.NULL_NOT_ALLOWED, Location.of(path));
        if (!node.isString()) return new Result.Failure(Code.TYPE_MISMATCH, Location.of(path));
        String value = node.asString();
        if (value.isEmpty() || value.codePointCount(0, value.length()) > maxLength) return new Result.Failure(Code.BOUND_VIOLATION, Location.of(path));
        return new Result.Success(null);
    }

    private Result requiredBoolean(JsonNode node, String path) {
        if (node == null || node.isMissingNode()) return new Result.Failure(Code.MISSING_FIELD, Location.of(path));
        if (node.isNull()) return new Result.Failure(Code.NULL_NOT_ALLOWED, Location.of(path));
        if (!node.isBoolean()) return new Result.Failure(Code.TYPE_MISMATCH, Location.of(path));
        return new Result.Success(null);
    }

    private Result requiredEnum(JsonNode node, String path, Class<? extends Enum<?>> type) {
        if (node == null || node.isMissingNode()) return new Result.Failure(Code.MISSING_FIELD, Location.of(path));
        if (node.isNull()) return new Result.Failure(Code.NULL_NOT_ALLOWED, Location.of(path));
        if (!node.isString()) return new Result.Failure(Code.TYPE_MISMATCH, Location.of(path));
        return enumValue(node.asString(), path, type, false);
    }

    private Result optionalEnum(JsonNode node, String path, Class<? extends Enum<?>> type) {
        if (node == null || node.isMissingNode()) return new Result.Failure(Code.MISSING_FIELD, Location.of(path));
        if (node.isNull()) return new Result.Success(null);
        if (!node.isString()) return new Result.Failure(Code.TYPE_MISMATCH, Location.of(path));
        return enumValue(node.asString(), path, type, true);
    }

    private Result optionalNumber(JsonNode node, String path, BigDecimal min, BigDecimal max) {
        if (node == null || node.isMissingNode()) return new Result.Failure(Code.MISSING_FIELD, Location.of(path));
        if (node.isNull()) return new Result.Success(null);
        if (!node.isNumber() || node.isBoolean()) return new Result.Failure(Code.TYPE_MISMATCH, Location.of(path));
        BigDecimal value;
        try {
            value = node.decimalValue();
        } catch (RuntimeException exception) {
            return new Result.Failure(Code.TYPE_MISMATCH, Location.of(path));
        }
        if (value.compareTo(min) < 0 || value.compareTo(max) > 0) return new Result.Failure(Code.BOUND_VIOLATION, Location.of(path));
        return new Result.Success(null);
    }

    private Result enumValue(String value, String path, Class<? extends Enum<?>> type, boolean allowNullName) {
        if (allowNullName && value == null) return new Result.Success(null);
        for (Enum<?> constant : type.getEnumConstants()) {
            if (constant.name().equals(value)) return new Result.Success(null);
        }
        return new Result.Failure(Code.INVALID_ENUM, Location.of(path));
    }

    private Result classifyParse(RuntimeException exception) {
        String type = exception.getClass().getSimpleName();
        if (type.contains("Unrecognized")) return new Result.Failure(Code.UNKNOWN_FIELD, Location.root());
        if (type.contains("Duplicate") || looksLikeDuplicate(exception)) {
            return new Result.Failure(Code.DUPLICATE_FIELD, Location.root());
        }
        if (type.contains("Trailing") || looksLikeTrailing(exception)) {
            return new Result.Failure(Code.TRAILING_CONTENT, Location.root());
        }
        if (exception instanceof StreamReadException) return new Result.Failure(Code.MALFORMED_JSON, Location.root());
        return new Result.Failure(Code.MALFORMED_JSON, Location.root());
    }

    private static boolean looksLikeDuplicate(RuntimeException exception) {
        String message = exception.getMessage();
        return message != null && message.startsWith("Duplicate ");
    }

    private static boolean looksLikeTrailing(RuntimeException exception) {
        String message = exception.getMessage();
        return message != null && message.startsWith("Trailing token ");
    }

    private static int firstNonWhitespace(String json) {
        for (int i = 0; i < json.length(); i++) {
            if (!Character.isWhitespace(json.charAt(i))) return i;
        }
        return -1;
    }

    private static String join(String path, String name) {
        return path.isEmpty() ? name : path + "." + name;
    }

    private static Set<String> union(Set<String> left, Set<String> right) {
        var names = new java.util.HashSet<>(left);
        names.addAll(right);
        return Set.copyOf(names);
    }

    @FunctionalInterface
    private interface ElementValidator {
        Result validate(JsonNode node, String path);
    }
}
