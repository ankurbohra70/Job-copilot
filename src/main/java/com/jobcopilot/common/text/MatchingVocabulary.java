package com.jobcopilot.common.text;

import tools.jackson.databind.json.JsonMapper;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MatchingVocabulary {
    public record Definition(String version, Map<String,List<String>> skills, Map<String,List<String>> roles, List<String> stopwords) {}
    private static final MatchingVocabulary STANDARD = load();
    private final Definition definition;
    private final List<SkillPhrase> skillPhrases;
    private MatchingVocabulary(Definition definition) {
        this.definition = definition;
        this.skillPhrases = phrases(definition);
    }
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
        return phrasePattern(normalize(phrase)).matcher(normalize(text)).find();
    }
    public boolean hasSkill(String text, String skill) {
        String canonical = canonical(skill);
        if (definition.skills().containsKey(canonical)) {
            return resolvedSkills(text).contains(canonical);
        }
        return contains(text, canonical);
    }
    public List<String> skills(String text) {
        return List.copyOf(resolvedSkills(text));
    }
    public boolean isKnownSkill(String skill) {
        return definition.skills().containsKey(canonical(skill));
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

    private Set<String> resolvedSkills(String text) {
        Set<String> detected = new TreeSet<>();
        for (SkillSpan span : skillSpans(text)) detected.add(span.canonical());
        return detected;
    }

    /** Offsets refer to normalize(text), not the original string. */
    public List<SkillSpan> skillSpans(String text) {
        String normalized = normalize(text);
        List<SkillSpan> spans = new ArrayList<>();
        for (SkillPhrase phrase : skillPhrases) {
            Matcher matcher = phrase.pattern().matcher(normalized);
            while (matcher.find()) {
                spans.add(new SkillSpan(phrase.canonical(), matcher.start(), matcher.end()));
            }
        }
        return resolveSpans(spans).spans();
    }

    // The work count permits deterministic complexity regression tests without timing thresholds.
    static Resolution resolveSpans(List<SkillSpan> input) {
        List<SkillSpan> spans = new ArrayList<>(input);
        spans.sort(Comparator.comparingInt(SkillSpan::start)
                .thenComparing(Comparator.comparingInt(SkillSpan::length).reversed()));
        List<SkillSpan> accepted = new ArrayList<>();
        List<SkillSpan> active = new ArrayList<>();
        long comparisons = 0;
        for (SkillSpan span : spans) {
            for (var iterator = active.iterator(); iterator.hasNext();) {
                comparisons++;
                if (iterator.next().end() <= span.start()) iterator.remove();
            }
            boolean subsumed = false;
            for (SkillSpan other : active) {
                comparisons++;
                if (!other.canonical().equals(span.canonical())
                        && other.start() <= span.start() && other.end() >= span.end()
                        && other.length() > span.length()) {
                    subsumed = true;
                    break;
                }
            }
            if (!subsumed) {
                accepted.add(span);
                active.add(span);
            }
        }
        return new Resolution(List.copyOf(accepted), comparisons);
    }
    record Resolution(List<SkillSpan> spans, long comparisons) {}

    private static List<SkillPhrase> phrases(Definition definition) {
        List<SkillPhrase> phrases = new ArrayList<>();
        definition.skills().forEach((canonical, aliases) -> {
            LinkedHashSet<String> terms = new LinkedHashSet<>();
            terms.add(canonical);
            terms.addAll(aliases);
            for (String term : terms) {
                String normalized = normalize(term);
                if (!normalized.isEmpty()) {
                    phrases.add(new SkillPhrase(canonical, phrasePattern(normalized)));
                }
            }
        });
        return List.copyOf(phrases);
    }

    private static Pattern phrasePattern(String normalizedPhrase) {
        return Pattern.compile("(?<![\\p{L}\\p{N}_+#.])" + Pattern.quote(normalizedPhrase) + "(?![\\p{L}\\p{N}_+#])");
    }

    private record SkillPhrase(String canonical, Pattern pattern) {}

    public record SkillSpan(String canonical, int start, int end) {
        int length() {
            return end - start;
        }
    }
}
