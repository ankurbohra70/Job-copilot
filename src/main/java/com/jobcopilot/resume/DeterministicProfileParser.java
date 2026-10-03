package com.jobcopilot.resume;

import com.jobcopilot.common.text.MatchingVocabulary;
import org.springframework.stereotype.Component;

import java.time.*;
import java.time.format.*;
import java.time.temporal.ChronoField;
import java.util.*;
import java.util.regex.*;

import static com.jobcopilot.resume.CandidateProfileData.*;

@Component
public class DeterministicProfileParser {
    public static final String VERSION = "rules-v5";
    private static final String DATE = "(?:\\d{4}-\\d{2}|(?:Jan(?:uary)?|Feb(?:ruary)?|Mar(?:ch)?|Apr(?:il)?|May|Jun(?:e)?|Jul(?:y)?|Aug(?:ust)?|Sep(?:tember)?|Oct(?:ober)?|Nov(?:ember)?|Dec(?:ember)?) +\\d{4}|\\d{4})";
    private static final Pattern RANGE = Pattern.compile("(?i)(" + DATE + ")\\s*[-–—]\\s*(" + DATE + "|present|current)");
    private static final Pattern TITLE_COMPANY = Pattern.compile("^(.+?)\\s+at\\s+(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern ROLE_WORD = Pattern.compile("(?i)\\b(engineer|developer|intern|architect|consultant|analyst|scientist|manager|lead)\\b");
    private static final Pattern PROJECT_LINK = Pattern.compile("(?i)\\s*(?:\\|\\s*github|\\s+link)\\s*$");
    private static final Pattern CGPA_SUFFIX = Pattern.compile("(?i)\\s*[-|]?\\s*CGPA\\b\\s*:?.*$");
    private static final Pattern COMPLETION_YEAR = Pattern.compile("(?<![\\p{L}\\p{N}_.%+-])(\\d{4})\\s*$");
    private static final Pattern GRADE_SUFFIX = Pattern.compile("(?i)\\s*[,|–—-]?\\s*(?:\\d+(?:\\.\\d+)?\\s*%|CGPA\\b.*)\\s*$");
    private static final Pattern SCHOOL_QUALIFICATION = Pattern.compile("(?i)^(?:Higher\\s+Secondary\\s+XII|Secondary\\s+X)(?:\\s*\\([^()]+\\))?$");
    private final MatchingVocabulary vocabulary = MatchingVocabulary.standard();

    public CandidateProfileData parse(String text, LocalDate assessedOn) {
        List<Section> sections = sections(text);
        List<WorkExperience> experience = new ArrayList<>();
        List<long[]> intervals = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        boolean complete = true;
        for (String block : sections.stream().filter(s -> s.kind().equals("work"))
                .flatMap(s -> workBlocks(s.text()).stream()).toList()) {
            Matcher range = RANGE.matcher(block);
            PartialDate start = null, end = null;
            boolean ongoing = false;
            if (range.find()) {
                start = date(range.group(1));
                ongoing = range.group(2).equalsIgnoreCase("present") || range.group(2).equalsIgnoreCase("current");
                end = ongoing ? null : date(range.group(2));
                boolean additionalRange = range.find();
                if (!additionalRange && start != null && start.month() != null
                        && (ongoing || end != null && end.month() != null)) {
                    YearMonth first = YearMonth.of(start.year(), start.month());
                    YearMonth last = ongoing ? YearMonth.from(assessedOn)
                            : YearMonth.of(end.year(), end.month()).plusMonths(1);
                    if (!first.isAfter(YearMonth.from(assessedOn)) && last.isAfter(first)
                            && !last.isAfter(YearMonth.from(assessedOn).plusMonths(1))) {
                        intervals.add(new long[]{first.getLong(ChronoField.PROLEPTIC_MONTH),
                                last.getLong(ChronoField.PROLEPTIC_MONTH)});
                    } else complete = false;
                } else complete = false;
            } else complete = false;
            WorkIdentity identity = workIdentity(block);
            if (identity.title() == null || identity.company() == null)
                warnings.add("Work entry identity is incomplete; title or company was left unparsed");
            experience.add(new WorkExperience(identity.title(), identity.company(), start, end, ongoing,
                    bullets(block), block));
        }
        if (experience.isEmpty()) complete = false;
        int months = unionMonths(intervals);
        if (!complete) warnings.add("Employment chronology is incomplete or ambiguous; total experience is UNKNOWN");

        List<Education> education = sections.stream().filter(s -> s.kind().equals("education"))
                .flatMap(s -> educationBlocks(s.text()).stream())
                .map(DeterministicProfileParser::education).toList();
        List<Project> projects = sections.stream().filter(s -> s.kind().equals("projects"))
                .flatMap(s -> projectBlocks(s.text()).stream()).toList();
        List<Evidence> evidence = new ArrayList<>();
        for (String skill : vocabulary.skills(text)) {
            String source = text.lines().filter(line -> vocabulary.hasSkill(line, skill)).findFirst().orElse("");
            evidence.add(new Evidence("SKILL", skill, source));
        }
        return new CandidateProfileData(vocabulary.skills(text), complete ? months : null,
                intervals.isEmpty() ? null : months, complete ? Assessment.KNOWN : Assessment.UNKNOWN,
                experience, education, projects, vocabulary.keywords(text),
                experience.stream().filter(e -> e.title() != null)
                        .flatMap(e -> vocabulary.roles(e.title()).stream()).distinct().sorted().toList(),
                evidence, warnings);
    }

    private static List<Section> sections(String text) {
        List<Section> sections = new ArrayList<>();
        String section = "other";
        StringBuilder content = new StringBuilder();
        for (String line : text.replace("\r", "").split("\n")) {
            String heading = MatchingVocabulary.normalize(line).replaceAll("\\s*:$", "");
            String recognized = switch (heading) {
                case "experience", "work experience", "professional experience", "employment", "employment history" -> "work";
                case "education", "academic background" -> "education";
                case "projects", "personal projects", "academic projects" -> "projects";
                case "skills", "technical skills", "summary", "professional summary", "certifications",
                        "achievements", "awards", "awards and achievements", "awards & achievements",
                        "leadership", "leadership experience", "positions of responsibility", "position of responsibility",
                        "leadership and achievements", "leadership & achievements" -> "other";
                default -> null;
            };
            if (recognized != null) {
                sections.add(new Section(section, content.toString()));
                section = recognized;
                content = new StringBuilder();
            } else content.append(line).append('\n');
        }
        sections.add(new Section(section, content.toString()));
        return List.copyOf(sections);
    }

    private List<String> workBlocks(String section) {
        List<String> lines = lines(section);
        List<Integer> starts = new ArrayList<>();
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            Matcher range = RANGE.matcher(line);
            if (isBullet(line) || !range.find()) continue;
            int start = !line.substring(0, range.start()).trim().isEmpty()
                    ? index : previousHeaderLine(lines, index);
            if (start >= 0 && (starts.isEmpty() || starts.getLast() != start)) starts.add(start);
        }
        return starts.isEmpty() ? blocks(section) : slices(lines, starts);
    }

    private static List<String> educationBlocks(String section) {
        List<String> lines = lines(section);
        List<Integer> starts = new ArrayList<>();
        for (int index = 0; index < lines.size(); index++) {
            EducationDate dates = educationDate(lines, index);
            if (dates == null) continue;
            int start = !lines.get(index).substring(0, dates.offset()).trim().isEmpty()
                    ? index : previousHeaderLine(lines, index);
            if (start >= 0 && (starts.isEmpty() || starts.getLast() != start)) starts.add(start);
        }
        return starts.isEmpty() ? blocks(section) : slices(lines, starts);
    }

    // Ranges retain their existing behavior. Completion years additionally require an education identity.
    private static EducationDate educationDate(List<String> lines, int index) {
        String line = lines.get(index);
        if (isBullet(line)) return null;
        Matcher range = RANGE.matcher(line);
        if (range.find()) return new EducationDate(range.start(), date(range.group(1)),
                range.group(2).matches("(?i)present|current") ? null : date(range.group(2)));
        Matcher year = COMPLETION_YEAR.matcher(line);
        if (!year.find()) return null;
        int value = Integer.parseInt(year.group(1));
        if (value < 1900 || value > 2200) return null;
        String identity = line.substring(0, year.start()).strip();
        if (identity.isEmpty()) {
            int previous = index - 1;
            while (previous >= 0 && lines.get(previous).isBlank()) previous--;
            if (previous < 0 || isBullet(lines.get(previous))) return null;
            identity = lines.get(previous).strip();
        }
        identity = cleanEducationIdentity(identity);
        String[] parts = identity.split("\\s+[-–—]\\s+", 2);
        boolean school = SCHOOL_QUALIFICATION.matcher(identity).matches()
                || parts.length == 2 && SCHOOL_QUALIFICATION.matcher(parts[1]).matches();
        String title = identity.replaceAll("[,()]", " ").replaceAll("\\s+", " ").strip();
        boolean academic = identity.matches("(?i).*\\b(university|college|school|institute|bachelor|master|b\\.sc|mca|ph\\.d)\\b.*")
                && !identity.matches("(?i).*\\b(award|awarded|scholarship|achievement|identifier|id|grade|score|rank|founded|established)\\b.*")
                && looksLikeProjectTitle(title);
        return school || academic ? new EducationDate(year.start(), null, date(year.group(1))) : null;
    }

    private static String cleanEducationIdentity(String value) {
        String cleaned = CGPA_SUFFIX.matcher(value).replaceFirst("");
        cleaned = GRADE_SUFFIX.matcher(cleaned).replaceFirst("");
        return cleaned.replaceAll("[|,\\s–—-]+$", "").strip();
    }

    private record EducationDate(int offset, PartialDate start, PartialDate end) {}

    private List<Project> projectBlocks(String section) {
        List<String> lines = lines(section);
        List<Project> result = new ArrayList<>();
        LogicalBullets bullets = new LogicalBullets();
        int start = -1;
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index).strip();
            ProjectLine kind = projectLine(lines, index, start < 0, bullets.active());
            switch (kind) {
                case HEADER -> {
                    if (start >= 0) result.add(project(lines, start, index, bullets.completed()));
                    start = index;
                    bullets = new LogicalBullets();
                }
                case TECHNOLOGY -> bullets.finish();
                case BULLET -> bullets.start(line);
                case CONTINUATION -> bullets.append(line);
                case OTHER -> { /* Preserve blank and unclassified lines in sourceText. */ }
            }
        }
        if (start >= 0) result.add(project(lines, start, lines.size(), bullets.completed()));
        return List.copyOf(result);
    }

    private ProjectLine projectLine(List<String> lines, int index, boolean first, boolean activeBullet) {
        String line = lines.get(index).strip();
        if (line.isEmpty()) return ProjectLine.OTHER;
        if (isBullet(line)) return ProjectLine.BULLET;
        if (technologyMetadata(line)) return ProjectLine.TECHNOLOGY;
        String title = PROJECT_LINK.matcher(line.split("\\|", 2)[0]).replaceFirst("").strip();
        boolean titleShape = looksLikeProjectTitle(title);
        boolean linked = PROJECT_LINK.matcher(line).find();
        int next = index + 1;
        while (next < lines.size() && lines.get(next).isBlank()) next++;
        boolean technologyFollows = next < lines.size() && technologyMetadata(lines.get(next).strip());
        boolean separatedBulletFollows = index > 0 && lines.get(index - 1).isBlank()
                && next < lines.size() && isBullet(lines.get(next));
        if (first || titleShape && (linked && (line.contains("|") || technologyFollows
                || next < lines.size() && isBullet(lines.get(next))) || technologyFollows || separatedBulletFollows))
            return ProjectLine.HEADER;
        return activeBullet ? ProjectLine.CONTINUATION : ProjectLine.OTHER;
    }

    private static boolean looksLikeProjectTitle(String title) {
        if (title.isEmpty() || title.length() > 160 || !Character.isUpperCase(title.codePointAt(0))
                || title.matches(".*[.!?;:]$")) return false;
        // Bare headers need title-like words, not merely a sentence whose first word is capitalized.
        return Arrays.stream(title.split("\\s+")).allMatch(word -> Character.isUpperCase(word.codePointAt(0))
                || word.matches("[\\d&/–—-]+") || Set.of("a", "an", "and", "of", "for", "the", "with", "to", "in").contains(word));
    }

    private boolean technologyMetadata(String line) {
        if (line.matches("(?i)^(?:technologies|tech stack|technology stack)\\s*:.*")) return true;
        // Lists have separators, multiple known skills, and short metadata labels rather than sentence clauses.
        if (!line.contains("|") && !line.contains(",")) return false;
        if (PROJECT_LINK.matcher(line).find()) return false;
        String[] items = line.split("[|,]");
        long known = Arrays.stream(items).filter(item -> !vocabulary.skills(item).isEmpty()).count();
        return known >= 2 && Arrays.stream(items).allMatch(item -> vocabulary.isKnownSkill(item.strip())
                || item.strip().matches("(?:[\\p{Lu}\\d][\\p{L}\\p{N}+#./&-]*|&)(?:\\s+(?:[\\p{Lu}\\d][\\p{L}\\p{N}+#./&-]*|&)){0,6}"))
                && !line.matches(".*[.!?;]$");
    }

    private static String joiningWhitespace(String line) {
        return line.replaceAll("[\\s\\p{Z}]+", " ").strip();
    }

    private static final class LogicalBullets {
        private final List<String> values = new ArrayList<>();
        private final StringBuilder current = new StringBuilder();

        boolean active() { return !current.isEmpty(); }

        void start(String line) {
            finish();
            current.append(joiningWhitespace(line));
        }

        void append(String line) {
            if (active() && !line.isBlank()) current.append(' ').append(joiningWhitespace(line));
        }

        void finish() {
            if (active()) {
                values.add(current.toString());
                current.setLength(0);
            }
        }

        List<String> completed() {
            finish();
            return List.copyOf(values);
        }
    }

    private WorkIdentity workIdentity(String block) {
        List<String> lines = lines(block);
        int rangeLine = -1;
        Matcher range = null;
        for (int index = 0; index < lines.size(); index++) {
            Matcher candidate = RANGE.matcher(lines.get(index));
            if (!isBullet(lines.get(index)) && candidate.find()) {
                rangeLine = index;
                range = candidate;
                break;
            }
        }
        if (rangeLine < 0) return headerIdentity(lines.stream()
                .filter(line -> !line.isBlank() && !isBullet(line)).findFirst().orElse(""), null);

        String rangeText = lines.get(rangeLine);
        String prefix = rangeText.substring(0, range.start()).replaceAll("[|,\\s]+$", "").trim();
        String identityLine = prefix;
        if (identityLine.isEmpty()) {
            for (int index = rangeLine - 1; index >= 0; index--) {
                if (!lines.get(index).isBlank() && !isBullet(lines.get(index))) {
                    identityLine = lines.get(index).trim();
                    break;
                }
            }
        }
        String following = null;
        for (int index = rangeLine + 1; index < lines.size(); index++) {
            String candidate = lines.get(index).trim();
            if (candidate.isEmpty()) continue;
            if (isBullet(candidate)) break;
            following = candidate;
            break;
        }
        return headerIdentity(identityLine, following);
    }

    private WorkIdentity headerIdentity(String line, String following) {
        String header = line.split("\\|", 2)[0].trim();
        Matcher at = TITLE_COMPANY.matcher(header);
        if (at.matches() && looksLikeRole(at.group(1)))
            return new WorkIdentity(at.group(1).trim(), cleanCompany(at.group(2)));

        String[] companyTitle = header.split("\\s+[-–—]\\s+", 2);
        if (companyTitle.length == 2 && looksLikeRole(companyTitle[1]))
            return new WorkIdentity(companyTitle[1].trim(), cleanCompany(companyTitle[0]));

        if (looksLikeRole(header)) return new WorkIdentity(header, cleanCompany(following));
        return new WorkIdentity(null, null);
    }

    private boolean looksLikeRole(String value) {
        return value != null && !value.isBlank() && !value.endsWith(".")
                && (!vocabulary.roles(value).isEmpty() || ROLE_WORD.matcher(value).find());
    }

    private static String cleanCompany(String value) {
        if (value == null) return null;
        String company = value.split("\\|", 2)[0].trim();
        int comma = company.indexOf(',');
        if (comma > 0) {
            String[] words = company.substring(0, comma).trim().split("\\s+");
            if (words.length > 1) company = String.join(" ", Arrays.copyOf(words, words.length - 1));
        }
        company = company.replaceAll("[|,\\s]+$", "").trim();
        return company.isEmpty() || company.endsWith(".") ? null : company;
    }

    private static Education education(String block) {
        List<String> lines = lines(block);
        PartialDate start = null, end = null;
        String institution = null, qualification = null;
        for (int rangeIndex = 0; rangeIndex < lines.size(); rangeIndex++) {
            EducationDate dates = educationDate(lines, rangeIndex);
            if (dates == null) continue;
            start = dates.start();
            end = dates.end();
            String prefix = cleanEducationIdentity(lines.get(rangeIndex).substring(0, dates.offset()));
            if (prefix.isEmpty() && rangeIndex > 0)
                prefix = cleanEducationIdentity(lines.get(previousHeaderLine(lines, rangeIndex)));
            String[] inline = prefix.split("\\s+[-–—]\\s+", 2);
            if (SCHOOL_QUALIFICATION.matcher(prefix).matches()) {
                qualification = prefix;
            } else if (inline.length == 2) {
                institution = cleanText(inline[0]);
                qualification = cleanQualification(inline[1]);
            } else if (!prefix.isEmpty()) institution = cleanText(prefix);
            if (qualification == null) {
                for (int index = rangeIndex + 1; index < lines.size(); index++) {
                    String candidate = lines.get(index).trim();
                    if (!candidate.isEmpty() && !isBullet(candidate)) {
                        qualification = cleanQualification(candidate);
                        break;
                    }
                }
            }
            break;
        }
        return new Education(institution, qualification, start, end, block);
    }

    private Project project(List<String> lines, int start, int end, List<String> bullets) {
        String block = String.join("\n", lines.subList(start, end)).trim();
        String header = lines.get(start).strip();
        String title = header;
        int separator = header.indexOf('|');
        if (separator >= 0) title = header.substring(0, separator);
        title = PROJECT_LINK.matcher(title).replaceFirst("").trim();
        return new Project(title.isEmpty() ? null : title, bullets, vocabulary.skills(block), block);
    }

    private static String cleanQualification(String value) {
        return cleanText(cleanEducationIdentity(value));
    }

    private static String cleanText(String value) {
        if (value == null) return null;
        String result = value.replaceAll("^[|,\\s]+|[|,\\s]+$", "").trim();
        return result.isEmpty() ? null : result;
    }

    private static int previousHeaderLine(List<String> lines, int before) {
        for (int index = before - 1; index >= 0; index--)
            if (!lines.get(index).isBlank() && !isBullet(lines.get(index))) return index;
        return before;
    }

    private static List<String> slices(List<String> lines, List<Integer> starts) {
        List<String> result = new ArrayList<>();
        for (int index = 0; index < starts.size(); index++) {
            int end = index + 1 < starts.size() ? starts.get(index + 1) : lines.size();
            String block = String.join("\n", lines.subList(starts.get(index), end)).trim();
            if (!block.isBlank()) result.add(block);
        }
        return List.copyOf(result);
    }

    private static List<String> lines(String section) {
        return Arrays.asList(section.replace("\r", "").split("\n", -1));
    }

    private static List<String> blocks(String section) {
        return Arrays.stream(section.trim().split("\n\\s*\n")).map(String::trim)
                .filter(value -> !value.isBlank()).toList();
    }

    private static boolean isBullet(String line) {
        return line.stripLeading().matches("^(?:[•*](?:.*)|[-–](?:[\\s\\p{Z}]+.*|$))");
    }

    private static List<String> bullets(String block) {
        LogicalBullets bullets = new LogicalBullets();
        block.lines().forEach(line -> {
            if (isBullet(line)) bullets.start(line);
            else bullets.append(line);
        });
        return bullets.completed();
    }

    private static PartialDate date(String value) {
        try {
            if (value.matches("\\d{4}")) return new PartialDate(Integer.parseInt(value), null, DatePrecision.YEAR);
            YearMonth month;
            if (value.matches("\\d{4}-\\d{2}")) month = YearMonth.parse(value);
            else {
                String[] parts = value.trim().split("\\s+");
                String shortName = parts[0].substring(0, 3);
                var format = new DateTimeFormatterBuilder().parseCaseInsensitive()
                        .appendPattern("MMM uuuu").toFormatter(Locale.ENGLISH);
                month = YearMonth.parse(shortName + " " + parts[1], format);
            }
            return new PartialDate(month.getYear(), month.getMonthValue(), DatePrecision.MONTH);
        } catch (DateTimeException | NumberFormatException exception) {
            return null;
        }
    }

    private static int unionMonths(List<long[]> ranges) {
        ranges.sort(Comparator.comparingLong(value -> value[0]));
        long start = 0, end = 0, total = 0;
        boolean first = true;
        for (long[] range : ranges) {
            if (first) {
                start = range[0];
                end = range[1];
                first = false;
            } else if (range[0] <= end) end = Math.max(end, range[1]);
            else {
                total += end - start;
                start = range[0];
                end = range[1];
            }
        }
        return Math.toIntExact(total + (first ? 0 : end - start));
    }

    private record WorkIdentity(String title, String company) {}
    private record Section(String kind, String text) {}
    private enum ProjectLine { HEADER, TECHNOLOGY, BULLET, CONTINUATION, OTHER }
}
