package com.jobcopilot.intelligence;

import java.util.ArrayList;
import java.util.List;

/** Internal source locations only; no offsets or expanded quotes enter the wire/accepted contract. */
final class SourceContext {
    static final int MAX_SOURCE_CHARS = 1_000_000;
    static final int MAX_CONTEXT_CODE_POINTS = 1024;
    static final int MAX_OCCURRENCES = 32;

    private SourceContext() {}

    /** Empty means unsupported. Never truncate a context or select a favorable occurrence. */
    static List<String> all(String source, String quote) {
        if (source.length() > MAX_SOURCE_CHARS || quote == null || quote.isBlank()) return List.of();
        List<String> contexts = new ArrayList<>();
        int from = 0;
        for (int at; (at = source.indexOf(quote, from)) >= 0; from = at + 1) {
            if (contexts.size() == MAX_OCCURRENCES) return List.of();
            int end = at + quote.length();
            if (splitSurrogate(source, at) || splitSurrogate(source, end)) return List.of();
            int left = at;
            while (left > 0 && !boundary(source, left - 1)) {
                if (at - left >= MAX_CONTEXT_CODE_POINTS * 2) return List.of();
                left--;
            }
            // A quote crossing sentence/clause boundaries is unsupported, not silently split.
            for (int i = at; i < end - 1; i++) if (boundary(source, i)) return List.of();
            int right = end;
            if (end == 0 || !boundary(source, end - 1)) {
                while (right < source.length() && !boundary(source, right)) {
                    if (right - left >= MAX_CONTEXT_CODE_POINTS * 2) return List.of();
                    right++;
                }
                if (right < source.length()) right++;
            }
            if (source.codePointCount(left, right) > MAX_CONTEXT_CODE_POINTS) return List.of();
            contexts.add(source.substring(left, right));
        }
        return List.copyOf(contexts);
    }

    private static boolean splitSurrogate(String text, int index) {
        return index > 0 && index < text.length() && Character.isHighSurrogate(text.charAt(index - 1))
                && Character.isLowSurrogate(text.charAt(index));
    }

    private static boolean boundary(String text, int i) {
        char c = text.charAt(i);
        if (c == '.') {
            int from = i;
            while (from > 0 && i - from < 4 && Character.isLetter(text.charAt(from - 1))) from--;
            String token = text.substring(from, i).toLowerCase(java.util.Locale.ROOT);
            if (java.util.Set.of("yr", "yrs", "mo", "mos", "min").contains(token)) return false;
        }
        // Commas, colons, conjunctions and parentheses stay attached to their modifiers.
        // Single line breaks can wrap a modifier ("No\nJava required"); retain them.
        boolean paragraph = c == '\n' && (i + 1 < text.length() && text.charAt(i + 1) == '\n'
                || i + 2 < text.length() && text.charAt(i + 1) == '\r' && text.charAt(i + 2) == '\n');
        return paragraph || c == ';' || c == '!' || c == '?'
                || c == '.' && (i + 1 == text.length() || Character.isWhitespace(text.charAt(i + 1)));
    }
}
