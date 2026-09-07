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
    public static final String VERSION = "rules-v1";
    private static final String DATE = "(?:\\d{4}-\\d{2}|(?:Jan(?:uary)?|Feb(?:ruary)?|Mar(?:ch)?|Apr(?:il)?|May|Jun(?:e)?|Jul(?:y)?|Aug(?:ust)?|Sep(?:tember)?|Oct(?:ober)?|Nov(?:ember)?|Dec(?:ember)?) +\\d{4}|\\d{4})";
    private static final Pattern RANGE = Pattern.compile("(?i)(" + DATE + ")\\s*[-–—]\\s*(" + DATE + "|present|current)");
    private static final Pattern TITLE_COMPANY = Pattern.compile("^(.+?)\\s+at\\s+(.+)$", Pattern.CASE_INSENSITIVE);
    private final MatchingVocabulary vocabulary = MatchingVocabulary.standard();

    public CandidateProfileData parse(String text, LocalDate assessedOn) {
        Map<String, StringBuilder> sections = new LinkedHashMap<>();
        String section = "other";
        for (String line : text.replace("\r", "").split("\n")) {
            String heading = MatchingVocabulary.normalize(line).replaceAll(":$", "");
            String recognized = switch (heading) {
                case "experience", "work experience", "professional experience", "employment", "employment history" -> "work";
                case "education", "academic background" -> "education";
                case "projects", "personal projects" -> "projects";
                case "skills", "technical skills", "summary", "certifications" -> "other";
                default -> null;
            };
            if (recognized != null) { section = recognized; sections.computeIfAbsent(section, k -> new StringBuilder()).append("\n\n"); }
            else sections.computeIfAbsent(section, k -> new StringBuilder()).append(line).append("\n");
        }
        List<WorkExperience> experience = new ArrayList<>();
        List<long[]> intervals = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        boolean complete = true;
        String workText = sections.getOrDefault("work", new StringBuilder()).toString().trim();
        for (String block : blocks(workText)) {
            Matcher range = RANGE.matcher(block);
            PartialDate start = null, end = null;
            boolean ongoing = false;
            if (range.find()) {
                start = date(range.group(1));
                ongoing = range.group(2).equalsIgnoreCase("present") || range.group(2).equalsIgnoreCase("current");
                end = ongoing ? null : date(range.group(2));
                // Multiple ranges in one block cannot reliably be attributed to a single employer.
                boolean additionalRange = range.find();
                if (!additionalRange && start != null && start.month() != null && (ongoing || end != null && end.month() != null)) {
                    YearMonth first = YearMonth.of(start.year(), start.month());
                    YearMonth last = ongoing ? YearMonth.from(assessedOn) : YearMonth.of(end.year(), end.month()).plusMonths(1);
                    if (!first.isAfter(YearMonth.from(assessedOn)) && last.isAfter(first)
                            && !last.isAfter(YearMonth.from(assessedOn).plusMonths(1)))
                        intervals.add(new long[]{first.getLong(ChronoField.PROLEPTIC_MONTH), last.getLong(ChronoField.PROLEPTIC_MONTH)});
                    else complete = false;
                } else complete = false;
            } else complete = false;
            String title = null, company = null;
            for (String line : block.split("\n")) {
                if (RANGE.matcher(line).find() || isBullet(line)) continue;
                Matcher identity = TITLE_COMPANY.matcher(line.trim());
                if (identity.matches()) { title = identity.group(1).trim(); company = identity.group(2).trim(); break; }
                if (!vocabulary.roles(line).isEmpty()) { title = line.trim(); break; }
            }
            experience.add(new WorkExperience(title, company, start, end, ongoing, bullets(block), block));
        }
        if (experience.isEmpty()) complete = false;
        int months = unionMonths(intervals);
        if (!complete) warnings.add("Employment chronology is incomplete or ambiguous; total experience is UNKNOWN");
        List<Education> education = blocks(sections.getOrDefault("education", new StringBuilder()).toString()).stream()
                .map(block -> new Education(null, null, null, null, block)).toList();
        List<Project> projects = blocks(sections.getOrDefault("projects", new StringBuilder()).toString()).stream()
                .map(block -> new Project(block.lines().findFirst().filter(line -> !isBullet(line)).orElse(null),
                        bullets(block), vocabulary.skills(block), block)).toList();
        List<Evidence> evidence = new ArrayList<>();
        for (String skill : vocabulary.skills(text)) {
            String source = text.lines().filter(line -> vocabulary.hasSkill(line, skill)).findFirst().orElse("");
            evidence.add(new Evidence("SKILL", skill, source));
        }
        return new CandidateProfileData(vocabulary.skills(text), complete ? months : null,
                intervals.isEmpty() ? null : months, complete ? Assessment.KNOWN : Assessment.UNKNOWN,
                experience, education, projects, vocabulary.keywords(text),
                experience.stream().filter(e -> e.title() != null).flatMap(e -> vocabulary.roles(e.title()).stream()).distinct().sorted().toList(),
                evidence, warnings);
    }
    private static List<String> blocks(String section) {
        return Arrays.stream(section.trim().split("\n\\s*\n")).map(String::trim).filter(s -> !s.isBlank()).toList();
    }
    private static boolean isBullet(String line) { return line.stripLeading().matches("^[•*\\-].*"); }
    private static List<String> bullets(String block) { return block.lines().filter(DeterministicProfileParser::isBullet).map(String::trim).toList(); }
    private static PartialDate date(String value) {
        try {
            if (value.matches("\\d{4}")) return new PartialDate(Integer.parseInt(value), null, DatePrecision.YEAR);
            YearMonth month;
            if (value.matches("\\d{4}-\\d{2}")) month = YearMonth.parse(value);
            else {
                String[] parts = value.trim().split("\\s+");
                String shortName = parts[0].substring(0, 3);
                var format = new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("MMM uuuu").toFormatter(Locale.ENGLISH);
                month = YearMonth.parse(shortName + " " + parts[1], format);
            }
            return new PartialDate(month.getYear(), month.getMonthValue(), DatePrecision.MONTH);
        } catch (DateTimeException | NumberFormatException exception) { return null; }
    }
    private static int unionMonths(List<long[]> ranges) {
        ranges.sort(Comparator.comparingLong(a -> a[0]));
        long start = 0, end = 0, total = 0;
        boolean first = true;
        for (long[] range : ranges) {
            if (first) { start = range[0]; end = range[1]; first = false; }
            else if (range[0] <= end) end = Math.max(end, range[1]);
            else { total += end - start; start = range[0]; end = range[1]; }
        }
        return Math.toIntExact(total + (first ? 0 : end - start));
    }
}
