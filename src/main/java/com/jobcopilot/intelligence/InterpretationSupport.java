package com.jobcopilot.intelligence;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Finite keyword support for model-supplied interpretations. Anything else is rejected, not repaired. */
final class InterpretationSupport {
    private InterpretationSupport() {}

    static boolean role(JobIntelligence.Role role, List<String> quotes) {
        String text = joined(quotes).toLowerCase(Locale.ROOT);
        return switch (role) {
            case BACKEND -> contains(text, "backend") || contains(text, "back-end") || contains(text, "back end");
            case FRONTEND -> contains(text, "frontend") || contains(text, "front-end") || contains(text, "front end");
            case FULL_STACK -> contains(text, "full-stack") || contains(text, "full stack") || contains(text, "fullstack");
            case DATA_ENGINEERING -> contains(text, "data engineering") || contains(text, "data engineer");
            case PLATFORM_DEVOPS -> contains(text, "devops") || contains(text, "platform engineer") || contains(text, "sre");
            case OTHER -> contains(text, "other");
        };
    }

    static boolean seniority(JobIntelligence.Seniority seniority, List<String> quotes) {
        String text = joined(quotes).toLowerCase(Locale.ROOT);
        return switch (seniority) {
            case INTERN -> contains(text, "intern");
            case ENTRY -> contains(text, "entry-level") || contains(text, "entry level") || contains(text, "junior");
            case MID -> contains(text, "mid-level") || contains(text, "mid level") || contains(text, "intermediate");
            case SENIOR -> contains(text, "senior");
            case LEAD -> contains(text, "lead");
            case STAFF_PLUS -> contains(text, "staff") || contains(text, "principal");
            case MANAGEMENT -> contains(text, "manager") || contains(text, "management") || contains(text, "director");
        };
    }

    private static String joined(List<String> quotes) {
        return String.join("\n", quotes);
    }

    private static boolean contains(String haystack, String word) {
        return Pattern.compile("(?<![\\p{L}\\p{N}\\p{M}_])" + Pattern.quote(word) + "(?![\\p{L}\\p{N}\\p{M}_])").matcher(haystack).find();
    }
}
