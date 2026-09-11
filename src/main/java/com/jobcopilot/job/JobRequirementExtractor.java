package com.jobcopilot.job;

import com.jobcopilot.common.text.MatchingVocabulary;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class JobRequirementExtractor {
    private static final Pattern MARKDOWN_HEADING = Pattern.compile("^#{1,6}\\s+");
    private static final Pattern BULLET_PREFIX = Pattern.compile("^[•*\\-–—](?!\\d)\\s*");
    private static final String REQUIRED_HEADINGS =
            "required qualifications|required skills|minimum qualifications|basic qualifications|"
                    + "must[- ]haves?|requirements|required";
    private static final String PREFERRED_HEADINGS =
            "preferred qualifications|preferred skills|nice[- ]to[- ]have|good[- ]to[- ]have|preferred|bonus";
    private static final String OTHER_HEADINGS =
            "responsibilities|responsibility|about the role|about us|about the company|about the team|"
                    + "the role|what you(?:'ll| will) do|what you(?:'ll| will) build|what you will be doing|"
                    + "your impact|our values|day[- ]to[- ]day|benefits|perks|compensation|how to apply|"
                    + "equal opportunity|overview|summary|the team|what we offer|who we are|who you are|"
                    + "job description|culture";
    private static final Pattern REQUIRED_HEADING_LINE = Pattern.compile(
            "^(?:" + REQUIRED_HEADINGS + ")\\s*:?\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern REQUIRED_HEADING_PREFIX = Pattern.compile(
            "^(?:" + REQUIRED_HEADINGS + ")\\s*:\\s*", Pattern.CASE_INSENSITIVE);
    private static final Pattern PREFERRED_HEADING_LINE = Pattern.compile(
            "^(?:" + PREFERRED_HEADINGS + ")\\s*:?\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern PREFERRED_HEADING_PREFIX = Pattern.compile(
            "^(?:" + PREFERRED_HEADINGS + ")\\s*:\\s*", Pattern.CASE_INSENSITIVE);
    private static final Pattern OTHER_NAMED_HEADING_LINE = Pattern.compile(
            "^(?:" + OTHER_HEADINGS + ")\\s*:?\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern OTHER_NAMED_HEADING_PREFIX = Pattern.compile(
            "^(?:" + OTHER_HEADINGS + ")\\s*:\\s*", Pattern.CASE_INSENSITIVE);
    private static final Pattern GENERIC_COLON_HEADING = Pattern.compile("^([^:]{1,80}):\\s*(.*)$");
    private static final Pattern MARKER = Pattern.compile(
            "(?i)(?<copula>\\b(?:is|are|was|were)\\s+)?(?<marker>must[- ]haves?|nice[- ]to[- ]have|"
                    + "good[- ]to[- ]have|required|preferred|bonus)\\b");
    private static final Pattern PROPOSITION_BOUNDARY = Pattern.compile(
            "(?i)\\band\\s+(?:you|we|they|our|candidates?|applicants?|the\\s+(?:team|company|platform)|this\\s+role)\\b");
    private static final Pattern CLAUSE_SPLIT = Pattern.compile("\\s*[;•]\\s+|\\.(?:\\s+|$)");
    private static final Pattern EXPERIENCE_CONJUNCTION = Pattern.compile("(?i)\\s+(?:and|but)\\s+|\\s*,\\s*");
    private static final String NUMBER = "(?<![\\p{L}\\p{N}.+\\-])([+-]?\\d+(?:\\.\\d+)?)";
    private static final Pattern INHERITED_YEARS = Pattern.compile(
            "(?i)\\s*(?:,|and|but)\\s*" + NUMBER + "\\s+(?:required|minimum|preferred|desired|bonus)\\b");
    private static final Pattern NESTED_LABEL = Pattern.compile(
            "(?i)^(?:(?:relevant|professional)\\s+experience|experience|education|skills|core skills|technical skills|qualifications|certifications)(?:\\s*:.*)?$");
    private static final Pattern UP_TO_YEARS = Pattern.compile(
            "(?i)\\bup\\s+to\\s+\\d+(?:\\.\\d+)?\\s*(?:years?|yrs?\\.?)\\b");
    private static final Pattern OR_YEARS = Pattern.compile(
            "(?i)\\b\\d+(?:\\.\\d+)?\\s+or\\s+\\d+(?:\\.\\d+)?\\s*(?:years?|yrs?\\.?)\\b");
    private static final Pattern VALID_DASH_RANGE = Pattern.compile(
            "(?i)" + NUMBER + "\\s*[-–—]\\s*([+-]?\\d+(?:\\.\\d+)?)\\s*\\+?\\s*(?:years?|yrs?\\.?)\\b");
    private static final Pattern VALID_TO_RANGE = Pattern.compile(
            "(?i)" + NUMBER + "\\s+to\\s+([+-]?\\d+(?:\\.\\d+)?)\\s*\\+?\\s*(?:years?|yrs?\\.?)\\b");
    private static final Pattern RANGE_LIKE = Pattern.compile(
            "(?i)\\b\\d+(?:\\.\\d+)?\\s*-?to-?\\s*\\d+(?:\\.\\d+)?\\s*(?:years?|yrs?\\.?)\\b");
    private static final Pattern MINIMUM_YEARS = Pattern.compile(
            "(?i)\\b(?:minimum|min\\.?|at\\s+least)\\s+" + NUMBER + "\\s*\\+?\\s*(?:years?|yrs?\\.?)\\b");
    private static final Pattern PLUS_YEARS = Pattern.compile(
            "(?i)" + NUMBER + "\\s*\\+\\s*(?:years?|yrs?\\.?)\\b");
    private static final Pattern STANDALONE_YEARS = Pattern.compile(
            "(?i)" + NUMBER + "\\s*(?:years?|yrs?\\.?)\\b");
    private static final Pattern REQUIRED_EXPERIENCE_MARKER = Pattern.compile(
            "(?i)\\b(?:required|must(?:[- ]have)?|minimum|min\\.?|at\\s+least)\\b");
    private static final Pattern PREFERRED_EXPERIENCE_MARKER = Pattern.compile(
            "(?i)\\b(?:preferred|preferably|ideally|desired|nice[- ]to[- ]have|good[- ]to[- ]have|bonus)\\b");
    private static final Pattern CANDIDATE_ACTOR = Pattern.compile(
            "(?i)\\b(?:you|candidate|candidates|applicant|applicants|someone)\\b");
    private static final Pattern NON_CANDIDATE_ACTOR = Pattern.compile(
            "(?i)\\b(?:compan(?:y|ies)|organization|organisation|firm|founders?|teams?|cto|managers?|leadership|"
                    + "products?|platforms?|customers?|users?)\\b|\\bour\\s+company\\b|\\bwe\\s+have\\b");
    private static final Pattern DURATION_ROLE = Pattern.compile(
            "(?i)\\b(?:ago|old|duration|degree|bachelor'?s?|master'?s|diploma|contract)\\b|"
                    + "\\bin\\s+business\\b|\\bin\\s+(?:the\\s+)?market\\b");
    private static final Pattern USAGE_DURATION = Pattern.compile(
            "(?i)\\b(?:used|users|customers|clients)\\b[\\s\\S]{0,80}\\bfor\\s+$");
    private static final Pattern EXPERIENCE_NOUN = Pattern.compile("(?i)\\bexperience\\b");
    private static final Pattern COMBINED_EXPERIENCE = Pattern.compile("(?i)\\bcombined\\s+experience\\b");

    private final MatchingVocabulary vocabulary = MatchingVocabulary.standard();

    record Extraction(List<String> requiredSkills, List<String> preferredSkills, BigDecimal minYearsExperience) {
        Extraction {
            requiredSkills = List.copyOf(requiredSkills);
            preferredSkills = List.copyOf(preferredSkills);
        }
    }

    public Extraction extract(String description) {
        if (description == null || description.isBlank()) {
            throw new JobRequirementExtractionException("Job description must be non-blank to extract requirements");
        }

        Map<String, Qualification> skills = new LinkedHashMap<>();
        List<BigDecimal> minima = new ArrayList<>();
        Qualification section = Qualification.NONE;

        for (String rawLine : description.replace("\r", "").split("\n", -1)) {
            String line = stripBullet(rawLine.strip());
            if (line.isEmpty()) {
                continue;
            }
            Heading heading = parseSectionContext(line);
            String content;
            if (heading != null) {
                section = heading.section();
                content = heading.remainder();
            } else {
                content = line;
            }
            if (content.isBlank()) {
                continue;
            }
            for (String clause : CLAUSE_SPLIT.split(content)) {
                if (clause.isBlank()) {
                    continue;
                }
                collectSkills(clause, section, skills);
                collectExperience(clause, section, minima);
            }
        }

        List<String> required = skills.entrySet().stream()
                .filter(entry -> entry.getValue() == Qualification.REQUIRED)
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
        List<String> preferred = skills.entrySet().stream()
                .filter(entry -> entry.getValue() == Qualification.PREFERRED)
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
        BigDecimal minimum = minima.stream().max(BigDecimal::compareTo).orElse(null);
        if (minimum != null && minimum.signum() <= 0) {
            minimum = null;
        }
        if (required.isEmpty() && preferred.isEmpty() && minimum == null) {
            throw new JobRequirementExtractionException(
                    "No recognized job requirements could be extracted from the job description");
        }
        return new Extraction(required, preferred, minimum);
    }

    private void collectSkills(String clause, Qualification section, Map<String, Qualification> skills) {
        clause = MatchingVocabulary.normalize(clause);
        List<MarkedGroup> groups = detectExplicitSkillGroups(clause);
        if (groups.isEmpty()) {
            putSkills(vocabulary.skills(clause), section == Qualification.REQUIRED
                    ? Qualification.REQUIRED
                    : Qualification.PREFERRED, skills);
            return;
        }
        char[] remainder = clause.toCharArray();
        for (MarkedGroup group : groups) {
            putSkills(vocabulary.skills(clause.substring(group.skillStart(), group.skillEnd())),
                    group.qualification(), skills);
            for (int index = group.skillStart(); index < group.skillEnd(); index++) {
                remainder[index] = ' ';
            }
        }
        putSkills(vocabulary.skills(new String(remainder)), Qualification.PREFERRED, skills);
    }

    private static void putSkills(List<String> detected, Qualification qualification, Map<String, Qualification> skills) {
        for (String skill : detected) {
            if (skills.get(skill) == Qualification.REQUIRED) {
                continue;
            }
            skills.put(skill, qualification);
        }
    }

    private List<MarkedGroup> detectExplicitSkillGroups(String text) {
        List<MarkedGroup> groups = new ArrayList<>();
        Matcher matcher = MARKER.matcher(text);
        while (matcher.find()) {
            Qualification qualification = markerQualification(matcher.group("marker"));
            boolean suffix = isSuffix(text, matcher);
            int skillStart;
            int skillEnd;
            if (suffix) {
                skillEnd = matcher.start();
                skillStart = skillListStart(text, skillEnd);
            } else {
                skillStart = matcher.end();
                skillEnd = skillListEnd(text, matcher.end());
            }
            if (skillStart >= skillEnd) {
                continue;
            }
            groups.add(new MarkedGroup(skillStart, skillEnd, qualification));
        }
        return groups;
    }

    private static Qualification markerQualification(String marker) {
        String normalized = marker.toLowerCase(Locale.ROOT);
        if (normalized.contains("preferred")
                || normalized.contains("nice")
                || normalized.contains("good")
                || normalized.contains("bonus")) {
            return Qualification.PREFERRED;
        }
        return Qualification.REQUIRED;
    }

    private boolean isSuffix(String text, Matcher marker) {
        if (marker.group("copula") != null) return true;
        if (marker.group("marker").toLowerCase(Locale.ROOT).startsWith("must")) return false;
        String before = text.substring(skillListStart(text, marker.start()), marker.start());
        return !before.stripTrailing().endsWith(",") && !vocabulary.skills(before).isEmpty();
    }

    private int skillListStart(String text, int markerStart) {
        int index = markerStart;
        boolean[] protectedCharacters = protectedSkillCharacters(text);
        while (index > 0) {
            char previous = text.charAt(index - 1);
            if (!protectedCharacters[index - 1] && (previous == '.' || previous == ';' || previous == '•')) {
                break;
            }
            if (previous == ',' && MARKER.matcher(text.substring(0, index - 1)).find()) {
                break;
            }
            index--;
        }
        Matcher previousMarker = MARKER.matcher(text.substring(0, markerStart));
        while (previousMarker.find()) index = Math.max(index, previousMarker.end());
        while (index < markerStart && Character.isWhitespace(text.charAt(index))) {
            index++;
        }
        return index;
    }

    private int skillListEnd(String text, int markerEnd) {
        int end = text.length();
        Matcher next = MARKER.matcher(text);
        if (next.find(markerEnd)) {
            int nextSkillStart = isSuffix(text, next)
                    ? skillListStart(text, next.start())
                    : next.start();
            end = Math.min(end, nextSkillStart);
        }
        Matcher proposition = PROPOSITION_BOUNDARY.matcher(text);
        if (proposition.find(markerEnd) && proposition.start() >= markerEnd) {
            end = Math.min(end, proposition.start());
        }
        int punctuation = indexOfClausePunctuation(text, markerEnd);
        if (punctuation >= 0) {
            end = Math.min(end, punctuation);
        }
        return Math.max(markerEnd, end);
    }

    private boolean[] protectedSkillCharacters(String text) {
        boolean[] protectedCharacters = new boolean[text.length()];
        for (var span : vocabulary.skillSpans(text)) {
            java.util.Arrays.fill(protectedCharacters, span.start(), span.end(), true);
        }
        return protectedCharacters;
    }

    private int indexOfClausePunctuation(String text, int from) {
        boolean[] protectedCharacters = protectedSkillCharacters(text);
        for (int index = from; index < text.length(); index++) {
            char character = text.charAt(index);
            if (!protectedCharacters[index] && (character == '.' || character == ';' || character == '•')) {
                return index;
            }
        }
        return -1;
    }

    private void collectExperience(String clause, Qualification section, List<BigDecimal> minima) {
        List<ExperienceNumberCandidate> candidates = findNumericExperienceCandidates(clause);
        for (int index = 0; index < candidates.size(); index++) {
            ExperienceNumberCandidate candidate = candidates.get(index);
            int previousEnd = index == 0 ? 0 : candidates.get(index - 1).end();
            int nextStart = index == candidates.size() - 1 ? clause.length() : candidates.get(index + 1).start();
            int windowStart = index == 0 ? 0 : boundAfterConjunction(clause, previousEnd, candidate.start());
            int windowEnd = index == candidates.size() - 1 ? clause.length() : boundBeforeConjunction(clause, candidate.end(), nextStart);
            String local = clause.substring(windowStart, windowEnd);
            ExperienceClassification classification = classifyExperience(candidate, local, clause, section);
            if (classification == ExperienceClassification.INVALID_MANDATORY) {
                throw new JobRequirementExtractionException(
                        candidate.lowerBound().signum() < 0 || candidate.lowerBound().compareTo(BigDecimal.valueOf(80)) > 0
                                ? "Extracted minimum experience is outside the allowed range of 0 to 80 years"
                                : "Extracted minimum experience must have at most two decimal places");
            }
            if (classification == ExperienceClassification.VALID_MANDATORY && candidate.lowerBound().signum() > 0) {
                minima.add(candidate.lowerBound());
            }
        }
    }

    private static int boundAfterConjunction(String text, int previousEnd, int currentStart) {
        Matcher matcher = EXPERIENCE_CONJUNCTION.matcher(text.substring(previousEnd, currentStart));
        int lastEnd = -1;
        while (matcher.find()) {
            lastEnd = matcher.end();
        }
        return lastEnd >= 0 ? previousEnd + lastEnd : previousEnd;
    }

    private static int boundBeforeConjunction(String text, int currentEnd, int nextStart) {
        Matcher matcher = EXPERIENCE_CONJUNCTION.matcher(text.substring(currentEnd, nextStart));
        if (matcher.find()) {
            return currentEnd + matcher.start();
        }
        return nextStart;
    }

    private static List<ExperienceNumberCandidate> findNumericExperienceCandidates(String text) {
        List<int[]> occupied = new ArrayList<>();
        List<ExperienceNumberCandidate> candidates = new ArrayList<>();
        addRangeCandidates(VALID_DASH_RANGE, text, occupied, candidates);
        addRangeCandidates(VALID_TO_RANGE, text, occupied, candidates);
        Matcher minimum = MINIMUM_YEARS.matcher(text);
        while (minimum.find()) {
            addIfFree(minimum.start(), minimum.end(), years(minimum.group(1)), Form.MINIMUM, occupied, candidates);
        }
        Matcher plus = PLUS_YEARS.matcher(text);
        while (plus.find()) {
            addIfFree(plus.start(), plus.end(), years(plus.group(1)), Form.PLUS, occupied, candidates);
        }
        occupyMatches(UP_TO_YEARS, text, occupied);
        occupyMatches(OR_YEARS, text, occupied);
        Matcher rangeLike = RANGE_LIKE.matcher(text);
        while (rangeLike.find()) {
            if (!overlaps(rangeLike.start(), rangeLike.end(), occupied)) {
                occupied.add(new int[]{rangeLike.start(), rangeLike.end()});
            }
        }
        Matcher standalone = STANDALONE_YEARS.matcher(text);
        while (standalone.find()) {
            addIfFree(standalone.start(), standalone.end(), years(standalone.group(1)), Form.STANDALONE,
                    occupied, candidates);
        }
        candidates.sort((left, right) -> Integer.compare(left.start(), right.start()));
        Matcher inherited = INHERITED_YEARS.matcher(text);
        while (inherited.find()) {
            boolean coordinated = candidates.stream().anyMatch(previous -> previous.end() <= inherited.start()
                    && text.substring(previous.end(), inherited.start()).matches(
                            "(?i)\\s*(?:required|minimum|preferred|desired|bonus)\\s*"));
            if (coordinated) {
                addIfFree(inherited.start(1), inherited.end(1), years(inherited.group(1)),
                        Form.STANDALONE, occupied, candidates);
            }
        }
        candidates.sort((left, right) -> Integer.compare(left.start(), right.start()));
        return candidates;
    }

    private static void addRangeCandidates(
            Pattern pattern,
            String text,
            List<int[]> occupied,
            List<ExperienceNumberCandidate> candidates
    ) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            BigDecimal lower = range(matcher.group(1), matcher.group(2));
            if (lower == null) {
                occupied.add(new int[]{matcher.start(), matcher.end()});
                continue;
            }
            addIfFree(matcher.start(), matcher.end(), lower, Form.RANGE, occupied, candidates);
        }
    }

    private static void occupyMatches(Pattern pattern, String text, List<int[]> occupied) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            occupied.add(new int[]{matcher.start(), matcher.end()});
        }
    }

    private static void addIfFree(
            int start,
            int end,
            BigDecimal years,
            Form form,
            List<int[]> occupied,
            List<ExperienceNumberCandidate> candidates
    ) {
        if (years == null || overlaps(start, end, occupied)) {
            return;
        }
        occupied.add(new int[]{start, end});
        candidates.add(new ExperienceNumberCandidate(start, end, years, form));
    }

    private static boolean overlaps(int start, int end, List<int[]> occupied) {
        for (int[] span : occupied) {
            if (start < span[1] && end > span[0]) {
                return true;
            }
        }
        return false;
    }

    private ExperienceClassification classifyExperience(
            ExperienceNumberCandidate candidate,
            String local,
            String clause,
            Qualification section
    ) {
        if (preferredOnlyExperience(local, section)) {
            return ExperienceClassification.VALID_PREFERRED;
        }
        if (nonCandidateDuration(candidate, local, clause)) {
            return ExperienceClassification.IGNORED_UNSUPPORTED;
        }
        if (!hasPositiveExperienceEvidence(candidate, local, clause, section))
            return ExperienceClassification.IGNORED_UNSUPPORTED;
        try {
            Job.validateExperience(candidate.lowerBound());
        } catch (IllegalArgumentException invalid) {
            return ExperienceClassification.INVALID_MANDATORY;
        }
        return ExperienceClassification.VALID_MANDATORY;
    }

    private static boolean preferredOnlyExperience(String local, Qualification section) {
        if (REQUIRED_EXPERIENCE_MARKER.matcher(local).find()) {
            return false;
        }
        return PREFERRED_EXPERIENCE_MARKER.matcher(local).find() || section == Qualification.PREFERRED;
    }

    private boolean nonCandidateDuration(ExperienceNumberCandidate candidate, String local, String clause) {
        if (DURATION_ROLE.matcher(local).find() || COMBINED_EXPERIENCE.matcher(local).find()) {
            return true;
        }
        String prefix = clause.substring(0, candidate.start());
        if (USAGE_DURATION.matcher(prefix).find()) {
            return true;
        }
        // The nearest subject before this quantity wins; an earlier "you" must not bless a manager's tenure.
        String subject = prefix.substring(Math.max(0, prefix.length() - 120));
        int candidateActor = lastMatch(CANDIDATE_ACTOR, subject);
        int otherActor = lastMatch(NON_CANDIDATE_ACTOR, subject);
        if (otherActor >= 0 || candidateActor >= 0) return otherActor > candidateActor;
        return NON_CANDIDATE_ACTOR.matcher(local).find();
    }

    private static int lastMatch(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        int last = -1;
        while (matcher.find()) last = matcher.start();
        return last;
    }

    private boolean hasPositiveExperienceEvidence(
            ExperienceNumberCandidate candidate,
            String local,
            String clause,
            Qualification section
    ) {
        if (candidate.form() == Form.MINIMUM || candidate.form() == Form.PLUS || candidate.form() == Form.RANGE) {
            return true;
        }
        if (REQUIRED_EXPERIENCE_MARKER.matcher(local).find()) {
            return true;
        }
        if (CANDIDATE_ACTOR.matcher(local).find() || CANDIDATE_ACTOR.matcher(clause).find()) {
            return true;
        }
        if (EXPERIENCE_NOUN.matcher(local).find()) {
            return true;
        }
        return section == Qualification.REQUIRED;
    }

    private static BigDecimal years(String text) {
        return new BigDecimal(text);
    }

    private static BigDecimal range(String lowerText, String upperText) {
        BigDecimal lower = years(lowerText);
        BigDecimal upper = years(upperText);
        if (lower.compareTo(upper) > 0) {
            return null;
        }
        return lower;
    }

    private Heading parseSectionContext(String line) {
        Matcher markdown = MARKDOWN_HEADING.matcher(line);
        boolean markdownHeading = markdown.lookingAt();
        String text = markdownHeading ? line.substring(markdown.end()).strip() : line;
        if (text.isEmpty()) {
            return null;
        }
        Heading named = namedHeading(text);
        if (named != null) {
            return named;
        }
        // Returning content preserves both the parent context and the experience noun before a quantity.
        if (NESTED_LABEL.matcher(text).matches()) return null;
        Matcher colon = GENERIC_COLON_HEADING.matcher(text);
        if (colon.matches()) {
            String label = colon.group(1).strip();
            String remainder = colon.group(2);
            if (vocabulary.skills(label).isEmpty() && vocabulary.skills(remainder).isEmpty()) {
                return new Heading(Qualification.NONE, remainder);
            }
            return null;
        }
        if (markdownHeading) {
            if (vocabulary.skills(text).isEmpty() && wordCount(text) <= 8 && !text.chars().anyMatch(Character::isDigit)) {
                return new Heading(Qualification.NONE, "");
            }
            return null;
        }
        return null;
    }

    private static Heading namedHeading(String text) {
        if (PREFERRED_HEADING_LINE.matcher(text).matches()) {
            return new Heading(Qualification.PREFERRED, "");
        }
        Matcher preferredPrefix = PREFERRED_HEADING_PREFIX.matcher(text);
        if (preferredPrefix.lookingAt()) {
            return new Heading(Qualification.PREFERRED, text.substring(preferredPrefix.end()));
        }
        if (REQUIRED_HEADING_LINE.matcher(text).matches()) {
            return new Heading(Qualification.REQUIRED, "");
        }
        Matcher requiredPrefix = REQUIRED_HEADING_PREFIX.matcher(text);
        if (requiredPrefix.lookingAt()) {
            return new Heading(Qualification.REQUIRED, text.substring(requiredPrefix.end()));
        }
        if (OTHER_NAMED_HEADING_LINE.matcher(text).matches()) {
            return new Heading(Qualification.NONE, "");
        }
        Matcher otherPrefix = OTHER_NAMED_HEADING_PREFIX.matcher(text);
        if (otherPrefix.lookingAt()) {
            return new Heading(Qualification.NONE, text.substring(otherPrefix.end()));
        }
        return null;
    }

    private static String stripBullet(String line) {
        return BULLET_PREFIX.matcher(line).replaceFirst("");
    }

    private static int wordCount(String text) {
        String trimmed = text.strip();
        if (trimmed.isEmpty()) {
            return 0;
        }
        return trimmed.split("\\s+").length;
    }

    private enum Qualification { NONE, REQUIRED, PREFERRED }

    private enum Form { RANGE, PLUS, MINIMUM, STANDALONE }

    private enum ExperienceClassification { VALID_MANDATORY, VALID_PREFERRED, IGNORED_UNSUPPORTED, INVALID_MANDATORY }

    private record Heading(Qualification section, String remainder) {}

    private record MarkedGroup(int skillStart, int skillEnd, Qualification qualification) {}

    private record ExperienceNumberCandidate(
            int start,
            int end,
            BigDecimal lowerBound,
            Form form
    ) {}
}
