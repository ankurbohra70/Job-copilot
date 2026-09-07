package com.jobcopilot.common.text;

import tools.jackson.databind.json.JsonMapper;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

public final class MatchingVocabulary {
    public record Definition(String version, Map<String,List<String>> skills, Map<String,List<String>> roles, List<String> stopwords) {}
    private static final MatchingVocabulary STANDARD = load();
    private final Definition definition;
    private MatchingVocabulary(Definition definition) { this.definition = definition; }
    public static MatchingVocabulary standard() { return STANDARD; }
    private static MatchingVocabulary load() {
        try (var input = MatchingVocabulary.class.getResourceAsStream("/matching-vocabulary.json")) {
            if (input == null) throw new IllegalStateException("Missing matching vocabulary");
            return new MatchingVocabulary(JsonMapper.builder().build().readValue(input, Definition.class));
        } catch (java.io.IOException exception) { throw new IllegalStateException("Cannot load matching vocabulary", exception); }
    }
    public String version() { return definition.version(); }
    public static String normalize(String text) {
        return text == null ? "" : Normalizer.normalize(text, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }
    public String canonical(String value) {
        String normalized = normalize(value);
        return definition.skills().entrySet().stream()
                .filter(e -> e.getKey().equals(normalized) || e.getValue().contains(normalized))
                .map(Map.Entry::getKey).findFirst().orElse(normalized);
    }
    public boolean contains(String text, String phrase) {
        return Pattern.compile("(?<![\\p{L}\\p{N}_+#.])" + Pattern.quote(normalize(phrase)) + "(?![\\p{L}\\p{N}_+#])")
                .matcher(normalize(text)).find();
    }
    public boolean hasSkill(String text, String skill) {
        String canonical = canonical(skill);
        return contains(text, canonical) || definition.skills().getOrDefault(canonical, List.of()).stream().anyMatch(a -> contains(text, a));
    }
    public List<String> skills(String text) {
        return definition.skills().keySet().stream().filter(s -> hasSkill(text, s)).sorted().toList();
    }
    public List<String> roles(String text) {
        return definition.roles().entrySet().stream().filter(e -> e.getValue().stream().anyMatch(a -> contains(text, a)))
                .map(Map.Entry::getKey).sorted().toList();
    }
    public List<String> keywords(String text) {
        String normalized = normalize(text);
        // Remove complete aliases first; technical tokens do not count again as context.
        var aliases = new ArrayList<String>();
        definition.skills().forEach((key, values) -> { aliases.add(key); aliases.addAll(values); });
        definition.roles().values().forEach(aliases::addAll);
        aliases.sort(Comparator.comparingInt(String::length).reversed());
        for (String alias : aliases)
            normalized = normalized.replaceAll("(?<![\\p{L}\\p{N}_+#.])" + Pattern.quote(alias) + "(?![\\p{L}\\p{N}_+#])", " ");
        return Pattern.compile("[\\p{L}][\\p{L}\\p{N}]{2,}").matcher(normalized).results()
                .map(m -> m.group()).filter(t -> !definition.stopwords().contains(t)).distinct().sorted().toList();
    }
}

