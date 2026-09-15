package com.jobcopilot.resume;

import com.jobcopilot.common.text.MatchingVocabulary;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

@Component
public final class CandidateProfileValidator {
    private static final int MAX_ITEMS = 500;
    private static final int MAX_TOTAL_ITEMS = 5_000;
    private static final int MAX_TOTAL_TEXT = 500_000;
    private static final int MAX_VALUE = 255;
    private static final int MAX_SOURCE = 20_000;
    private final MatchingVocabulary vocabulary = MatchingVocabulary.standard();

    public CandidateProfileData canonicalize(CandidateProfileData data) {
        if (data == null) throw new IllegalArgumentException("Candidate profile data is required");
        if (data.totalExperienceMonths() != null && data.totalExperienceMonths() > 960)
            throw new IllegalArgumentException("totalExperienceMonths must be between 0 and 960");
        if (data.observedExperienceMonths() != null
                && (data.observedExperienceMonths() < 0 || data.observedExperienceMonths() > 960))
            throw new IllegalArgumentException("observedExperienceMonths must be between 0 and 960");

        List<CandidateProfileData.WorkExperience> work = bounded(data.workExperience(), "workExperience").stream()
                .map(this::work).toList();
        List<CandidateProfileData.Education> education = bounded(data.education(), "education").stream()
                .map(this::education).toList();
        List<CandidateProfileData.Project> projects = bounded(data.projects(), "projects").stream()
                .map(this::project).toList();
        List<CandidateProfileData.Evidence> evidence = bounded(data.evidence(), "evidence").stream()
                .map(this::evidence).toList();

        CandidateProfileData result = new CandidateProfileData(
                canonical(data.skills(), "skills", true, true),
                data.totalExperienceMonths(),
                data.observedExperienceMonths(),
                java.util.Objects.requireNonNull(data.experienceAssessment(), "experienceAssessment is required"),
                work, education, projects,
                canonical(data.keywords(), "keywords", false, true),
                canonical(data.roleCategories(), "roleCategories", false, true),
                evidence, canonical(data.warnings(), "warnings", false, false));
        validateAggregateBounds(result);
        return result;
    }

    private CandidateProfileData.WorkExperience work(CandidateProfileData.WorkExperience value) {
        if (value == null) throw new IllegalArgumentException("workExperience must not contain null");
        dates(value.start(), value.end(), value.ongoing(), "workExperience");
        return new CandidateProfileData.WorkExperience(text(value.title(), MAX_VALUE, true),
                text(value.company(), MAX_VALUE, true), value.start(), value.end(), value.ongoing(),
                canonical(value.bullets(), "workExperience.bullets", false, false),
                text(value.sourceText(), MAX_SOURCE, true, true));
    }

    private CandidateProfileData.Education education(CandidateProfileData.Education value) {
        if (value == null) throw new IllegalArgumentException("education must not contain null");
        dates(value.start(), value.end(), false, "education");
        return new CandidateProfileData.Education(text(value.institution(), MAX_VALUE, true),
                text(value.qualification(), MAX_VALUE, true), value.start(), value.end(),
                text(value.sourceText(), MAX_SOURCE, true, true));
    }

    private CandidateProfileData.Project project(CandidateProfileData.Project value) {
        if (value == null) throw new IllegalArgumentException("projects must not contain null");
        return new CandidateProfileData.Project(text(value.title(), MAX_VALUE, true),
                canonical(value.bullets(), "projects.bullets", false, false),
                canonical(value.technologies(), "projects.technologies", true, true),
                text(value.sourceText(), MAX_SOURCE, true, true));
    }

    private CandidateProfileData.Evidence evidence(CandidateProfileData.Evidence value) {
        if (value == null) throw new IllegalArgumentException("evidence must not contain null");
        return new CandidateProfileData.Evidence(text(value.kind(), MAX_VALUE, false),
                text(value.value(), MAX_VALUE, false), text(value.sourceText(), MAX_SOURCE, false, true));
    }

    private void dates(CandidateProfileData.PartialDate start, CandidateProfileData.PartialDate end,
            boolean ongoing, String field) {
        partial(start, field + ".start"); partial(end, field + ".end");
        if (ongoing && end != null) throw new IllegalArgumentException(field + " ongoing entry must not have an end date");
        if (start != null && end != null && compare(start, end) > 0)
            throw new IllegalArgumentException(field + " start date must not be after end date");
    }

    private static void partial(CandidateProfileData.PartialDate value, String field) {
        if (value == null) return;
        if (value.year() < 1900 || value.year() > 2200) throw new IllegalArgumentException(field + " year is invalid");
        if (value.precision() == null) throw new IllegalArgumentException(field + " precision is required");
        if (value.precision() == CandidateProfileData.DatePrecision.MONTH
                && (value.month() == null || value.month() < 1 || value.month() > 12))
            throw new IllegalArgumentException(field + " month is required for MONTH precision");
        if (value.precision() == CandidateProfileData.DatePrecision.YEAR && value.month() != null)
            throw new IllegalArgumentException(field + " month must be absent for YEAR precision");
    }

    private static int compare(CandidateProfileData.PartialDate left, CandidateProfileData.PartialDate right) {
        int year = Integer.compare(left.year(), right.year());
        if (year != 0) return year;
        return Integer.compare(left.month() == null ? 1 : left.month(), right.month() == null ? 12 : right.month());
    }

    private List<String> canonical(List<String> values, String field, boolean skills, boolean sorted) {
        List<String> source = bounded(values, field);
        LinkedHashMap<String, String> unique = new LinkedHashMap<>();
        for (String value : source) {
            String cleaned = text(value, MAX_VALUE, false);
            if (skills) cleaned = vocabulary.canonical(cleaned);
            else if (sorted) cleaned = MatchingVocabulary.normalize(cleaned);
            String key = cleaned.toLowerCase(Locale.ROOT);
            unique.putIfAbsent(key, cleaned);
        }
        return sorted ? unique.values().stream().sorted().toList() : List.copyOf(unique.values());
    }

    private static <T> List<T> bounded(List<T> values, String field) {
        if (values == null) throw new IllegalArgumentException(field + " is required");
        if (values.size() > MAX_ITEMS) throw new IllegalArgumentException(field + " exceeds " + MAX_ITEMS + " items");
        return new ArrayList<>(values);
    }

    private static String text(String value, int max, boolean nullable) {
        return text(value, max, nullable, false);
    }

    private static String text(String value, int max, boolean nullable, boolean multiline) {
        if (value == null) {
            if (nullable) return null;
            throw new IllegalArgumentException("Candidate profile text value is required");
        }
        String cleaned = value.strip();
        if (cleaned.isEmpty()) {
            if (nullable) return null;
            throw new IllegalArgumentException("Candidate profile text value must not be blank");
        }
        if (cleaned.length() > max || cleaned.chars().anyMatch(character ->
                Character.isISOControl(character)
                        && !(multiline && (character == '\n' || character == '\r' || character == '\t'))))
            throw new IllegalArgumentException("Candidate profile text value is invalid");
        return cleaned;
    }

    private static void validateAggregateBounds(CandidateProfileData data) {
        long items = data.skills().size() + data.workExperience().size() + data.education().size()
                + data.projects().size() + data.keywords().size() + data.roleCategories().size()
                + data.evidence().size() + data.warnings().size();
        long characters = characters(data.skills()) + characters(data.keywords())
                + characters(data.roleCategories()) + characters(data.warnings());
        for (CandidateProfileData.WorkExperience value : data.workExperience()) {
            items += value.bullets().size();
            characters += characters(value.title(), value.company(), value.sourceText())
                    + characters(value.bullets());
        }
        for (CandidateProfileData.Education value : data.education())
            characters += characters(value.institution(), value.qualification(), value.sourceText());
        for (CandidateProfileData.Project value : data.projects()) {
            items += value.bullets().size() + value.technologies().size();
            characters += characters(value.title(), value.sourceText())
                    + characters(value.bullets()) + characters(value.technologies());
        }
        for (CandidateProfileData.Evidence value : data.evidence())
            characters += characters(value.kind(), value.value(), value.sourceText());
        if (items > MAX_TOTAL_ITEMS)
            throw new IllegalArgumentException("Candidate profile exceeds " + MAX_TOTAL_ITEMS + " total items");
        if (characters > MAX_TOTAL_TEXT)
            throw new IllegalArgumentException("Candidate profile exceeds " + MAX_TOTAL_TEXT + " text characters");
    }

    private static long characters(List<String> values) {
        return values.stream().mapToLong(String::length).sum();
    }

    private static long characters(String... values) {
        long result = 0;
        for (String value : values) if (value != null) result += value.length();
        return result;
    }
}
