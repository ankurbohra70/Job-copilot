package com.jobcopilot.intelligence;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import static com.jobcopilot.intelligence.JobIntelligence.Scope;

/** Closed V1 grammar over a complete source clause, never over model-selected wording. */
final class ExperienceSupport {
    private static final Set<String> GENERAL = Set.of("", "experience", "of experience", "of software engineering experience",
            "of professional development experience", "of professional experience", "of development experience",
            "of work experience", "of overall experience", "of total experience");
    private static final Set<String> RELEVANT = Set.of("of relevant experience", "of backend experience", "of frontend experience");
    private static final Set<String> SKILLS = Set.of("java", "spring boot", "kubernetes", "python", "c", "c++", "c#",
            "javascript", "sql", "node.js", "react", "go", "r");
    private static final Set<String> ALTERNATIVES = Set.of("degree", "a degree", "bachelor's degree", "a bachelor's degree",
            "master's degree", "a master's degree", "equivalent certification", "an equivalent certification",
            "certification", "a certification", "equivalent experience");
    private static final Pattern PRELUDE = Pattern.compile("(?:(?:must(?: have)?|you must(?: have)?|required|preferred|preferably|either) )*");
    private static final Pattern TAIL_IMPORTANCE = Pattern.compile("(?: (?:is |are )?(?:required|preferred|optional))$");
    private static final Pattern CONDITIONAL = Pattern.compile("\\b(?:or|if|unless|in lieu of|equivalent experience)\\b");

    record Supported(ExperienceForm form, Scope scope, boolean conditional) {}

    static Supported from(String context) {
        var located = ExperienceForm.locateSource(context);
        if (located == null) return null;
        var form = located.form();
        if (form.upper() != null && form.lower().compareTo(form.upper()) > 0) return null;
        String prefix = normalized(context.substring(0, located.start()));
        String suffix = normalized(context.substring(located.end())).replaceFirst("[.;!?]+$", "").strip();
        boolean conditional = false;
        if (prefix.startsWith("if ") || prefix.startsWith("unless ") || prefix.startsWith("in lieu of ")) {
            int comma = prefix.indexOf(',');
            if (comma < 0) return null;
            conditional = true;
            prefix = prefix.substring(comma + 1).strip();
        }
        if (!PRELUDE.matcher(prefix.isEmpty() ? "" : prefix + " ").matches()) return null;
        boolean either = Pattern.compile("\\beither\\b").matcher(prefix).find();
        suffix = TAIL_IMPORTANCE.matcher(" " + suffix).replaceFirst("").strip();
        var marker = CONDITIONAL.matcher(suffix);
        if (marker.find()) {
            String operator = marker.group();
            String tail = suffix.substring(marker.end()).strip();
            if (operator.equals("or")) {
                if (!ALTERNATIVES.contains(tail)) return null;
            } else if (operator.equals("equivalent experience")) {
                if (!tail.isEmpty()) return null;
            } else if (tail.isBlank()) return null;
            conditional = true;
            suffix = suffix.substring(0, marker.start()).strip();
        } else if (either) return null;
        Scope scope;
        if (GENERAL.contains(suffix)) scope = Scope.OVERALL;
        else if (RELEVANT.contains(suffix)) scope = Scope.RELEVANT;
        else {
            String binding = suffix;
            if (binding.startsWith("of ")) binding = binding.substring(3);
            else if (binding.startsWith("with ")) binding = binding.substring(5);
            else if (binding.startsWith("using ")) binding = binding.substring(6);
            else if (binding.startsWith("in ")) binding = binding.substring(3);
            else return null;
            if (binding.endsWith(" experience")) binding = binding.substring(0, binding.length() - 11);
            if (!SKILLS.contains(binding)) return null;
            scope = Scope.SKILL_SPECIFIC;
        }
        return new Supported(form, scope, conditional);
    }

    private static String normalized(String text) {
        return text.toLowerCase(Locale.ROOT).replace('\u2019', '\'').strip().replaceAll("\\s+", " ");
    }
}
