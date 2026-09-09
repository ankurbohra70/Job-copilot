package com.jobcopilot.intelligence;

final class TechnologyTokens {
    private TechnologyTokens() {}

    static boolean contains(String haystack, String name) {
        if (haystack == null || name == null || name.isEmpty()) return false;
        int from = 0;
        while (from <= haystack.length() - name.length()) {
            int index = haystack.indexOf(name, from);
            if (index < 0) return false;
            if (bounded(haystack, index, name.length())) return true;
            from = index + 1;
        }
        return false;
    }

    private static boolean bounded(String haystack, int start, int length) {
        if (start > 0 && identifierChar(haystack.codePointBefore(start))) return false;
        int end = start + length;
        return end >= haystack.length() || !identifierChar(haystack.codePointAt(end));
    }

    private static boolean identifierChar(int value) {
        return Character.isLetterOrDigit(value) || Character.getType(value) == Character.NON_SPACING_MARK
                || Character.getType(value) == Character.COMBINING_SPACING_MARK || value == '_' || value == '#' || value == '+';
    }
}
