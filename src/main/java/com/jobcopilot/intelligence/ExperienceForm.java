package com.jobcopilot.intelligence;

import java.math.BigDecimal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static com.jobcopilot.intelligence.JobIntelligence.Unit;

/** Finite experience expressions that Java can verify from clause text. */
final class ExperienceForm {
    private static final Pattern MORE_THAN = Pattern.compile(
            "(?i)\\bmore\\s+than\\s+(\\d+(?:\\.\\d+)?)\\s*(years?|yrs?\\.?|months?|mos?\\.?)(?![\\p{L}\\p{N}_])");
    private static final Pattern AT_LEAST = Pattern.compile(
            "(?i)\\b(?:at\\s+least|minimum|min\\.)\\s+(\\d+(?:\\.\\d+)?)\\s*\\+?\\s*(years?|yrs?\\.?|months?|mos?\\.?)(?![\\p{L}\\p{N}_])");
    private static final Pattern PLUS = Pattern.compile(
            "(?i)(?<![\\p{L}\\p{N}.+–—-])(\\d+(?:\\.\\d+)?)\\s*\\+\\s*(years?|yrs?\\.?|months?|mos?\\.?)(?![\\p{L}\\p{N}_])");
    private static final Pattern RANGE = Pattern.compile(
            "(?i)(?<![\\p{L}\\p{N}.+–—-])(\\d+(?:\\.\\d+)?)\\s*[-–—]\\s*(\\d+(?:\\.\\d+)?)\\s*(years?|yrs?\\.?|months?|mos?\\.?)(?![\\p{L}\\p{N}_])");
    private static final Pattern SIMPLE = Pattern.compile(
            "(?i)(?<![\\p{L}\\p{N}.+–—-])(\\d+(?:\\.\\d+)?)\\s*(years?|yrs?\\.?|months?|mos?\\.?)(?![\\p{L}\\p{N}_])");

    private final boolean exclusive;
    private final BigDecimal lower;
    private final BigDecimal upper;
    private final Unit unit;

    private ExperienceForm(boolean exclusive, BigDecimal lower, BigDecimal upper, Unit unit) {
        this.exclusive = exclusive;
        this.lower = lower;
        this.upper = upper;
        this.unit = unit;
    }

    boolean exclusive() { return exclusive; }
    BigDecimal lower() { return lower; }
    BigDecimal upper() { return upper; }
    Unit unit() { return unit; }

    record Located(ExperienceForm form, int start, int end) {}

    /** One complete quantity expression in bounded source context; multiple quantities fail closed. */
    static Located locateSource(String text) {
        for (Pattern pattern : new Pattern[] {MORE_THAN, AT_LEAST, PLUS, RANGE, SIMPLE}) {
            Matcher match = pattern.matcher(text);
            if (!match.find()) continue;
            int start = match.start(), end = match.end();
            Matcher quantities = SIMPLE.matcher(text);
            while (quantities.find()) {
                if (quantities.start() < start || quantities.end() > end) return null;
            }
            if (match.find()) return null;
            return new Located(parse(text.substring(start, end)), start, end);
        }
        return null;
    }

    boolean sameQuantity(ExperienceForm other) {
        return other != null && exclusive == other.exclusive && unit == other.unit
                && lower.compareTo(other.lower) == 0
                && (upper == null ? other.upper == null : other.upper != null && upper.compareTo(other.upper) == 0);
    }

    static ExperienceForm parse(String text) {
        if (text == null || text.isBlank()) return null;
        Matcher more = MORE_THAN.matcher(text);
        if (more.find()) return new ExperienceForm(true, number(more.group(1)), null, unit(more.group(2)));
        Matcher least = AT_LEAST.matcher(text);
        if (least.find()) return new ExperienceForm(false, number(least.group(1)), null, unit(least.group(2)));
        Matcher plus = PLUS.matcher(text);
        if (plus.find()) return new ExperienceForm(false, number(plus.group(1)), null, unit(plus.group(2)));
        Matcher range = RANGE.matcher(text);
        if (range.find()) return new ExperienceForm(false, number(range.group(1)), number(range.group(2)), unit(range.group(3)));
        Matcher simple = SIMPLE.matcher(text);
        if (simple.find()) return new ExperienceForm(false, number(simple.group(1)), null, unit(simple.group(2)));
        return null;
    }

    static boolean impliesRequired(String text) {
        return text != null && Pattern.compile("(?i)\\b(?:at\\s+least|minimum|min\\.|\\d+\\s*\\+)").matcher(text).find();
    }

    private static BigDecimal number(String value) {
        return new BigDecimal(value);
    }

    private static Unit unit(String value) {
        String normalized = value.toLowerCase(java.util.Locale.ROOT);
        return normalized.startsWith("month") || normalized.startsWith("mo") ? Unit.MONTHS : Unit.YEARS;
    }
}
