package com.jobcopilot.intelligence;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import static com.jobcopilot.intelligence.JobIntelligence.Evidence;
import static com.jobcopilot.intelligence.JobIntelligence.ExperienceClause;
import static com.jobcopilot.intelligence.JobIntelligence.Importance;
import static com.jobcopilot.intelligence.JobIntelligence.Qualification;
import static com.jobcopilot.intelligence.JobIntelligence.Skill;
import static com.jobcopilot.intelligence.JobIntelligence.Source;
import static com.jobcopilot.intelligence.JobIntelligence.TextClaim;
import static com.jobcopilot.intelligence.JobIntelligence.Uncertainty;
import static com.jobcopilot.intelligence.JobIntelligence.UncertaintyCode;
import static com.jobcopilot.intelligence.JobIntelligence.Unit;
import static com.jobcopilot.intelligence.JobIntelligenceResult.Code;
import static com.jobcopilot.intelligence.JobIntelligenceResult.Location;

/**
 * Conservative V1 grounding. Finite Java-verifiable constructions only; exact quotes prove provenance,
 * not semantic entailment. Canonical requirements are never evidence.
 */
final class JobIntelligenceValidator {
    static final String POLICY_VERSION = "job-intelligence-grounding-v1-source-context-1";
    private static final Pattern REQUIRED_MARKER = Pattern.compile(
            "(?i)\\b(?:must(?:[- ]have)?|required|you must|is required|are required)\\b");
    private static final Pattern PREFERRED_MARKER = Pattern.compile(
            "(?i)\\b(?:preferred|preferably|nice[- ]to[- ]have|good[- ]to[- ]have|bonus)\\b");
    private static final Pattern NEGATION = Pattern.compile(
            "(?i)\\b(?:no|not|without|optional|except)\\b");
    private static final Pattern ALTERNATIVE = Pattern.compile("(?i)\\b(?:or|either|if|unless|in lieu of)\\b");
    private static final BigDecimal TWELVE = new BigDecimal("12");

    private final MinimumExperienceDeriver deriver = new MinimumExperienceDeriver();

    sealed interface Result {
        record Success(JobIntelligence intelligence) implements Result {}
        record Failure(Code code, Location location) implements Result {}
    }

    Result validate(JobIntelligenceModel.Output candidate, String title, String description) {
        String rawTitle = title == null ? "" : title;
        String rawDescription = description == null ? "" : description;
        if (rawTitle.length() > SourceContext.MAX_SOURCE_CHARS || rawDescription.length() > SourceContext.MAX_SOURCE_CHARS)
            return new Result.Failure(Code.UNSUPPORTED_CLAIM_FORM, Location.root());
        Map<String, Evidence> evidenceById = new LinkedHashMap<>();
        Map<String, List<String>> sourceContexts = new LinkedHashMap<>();
        for (int i = 0; i < candidate.evidence().size(); i++) {
            Evidence evidence = candidate.evidence().get(i);
            String path = "evidence[" + i + "]";
            if (evidenceById.containsKey(evidence.id())) {
                return new Result.Failure(Code.DUPLICATE_EVIDENCE_ID, Location.of(path + ".id"));
            }
            String source = evidence.source() == Source.TITLE ? rawTitle : rawDescription;
            if (!containsExact(source, evidence.quote())) {
                return new Result.Failure(Code.EVIDENCE_QUOTE_MISMATCH, Location.of(path + ".quote"));
            }
            evidenceById.put(evidence.id(), evidence);
            List<String> contexts = SourceContext.all(source, evidence.quote());
            if (contexts.isEmpty()) return new Result.Failure(Code.UNSUPPORTED_CLAIM_FORM, Location.of(path));
            sourceContexts.put(evidence.id(), contexts);
        }
        Result refs = references(candidate, evidenceById);
        if (refs instanceof Result.Failure failure) return failure;
        Result skills = skills(candidate.facts().skills(), evidenceById, sourceContexts);
        if (skills instanceof Result.Failure failure) return skills;
        List<ExperienceForm> sourceForms = new ArrayList<>();
        Result clauses = experience(candidate.facts().experienceClauses(), evidenceById, sourceContexts, sourceForms);
        if (clauses instanceof Result.Failure failure) return clauses;
        Result qualifications = qualifications(candidate.facts().qualifications(), evidenceById, sourceContexts);
        if (qualifications instanceof Result.Failure failure) return qualifications;
        Result interpretations = interpretations(candidate, evidenceById, sourceContexts);
        if (interpretations instanceof Result.Failure failure) return interpretations;
        Result uncertainties = uncertainties(candidate.uncertainties(), evidenceById, candidate);
        if (uncertainties instanceof Result.Failure failure) return uncertainties;
        JobIntelligence.MinimumExperience minimum = deriver.derive(candidate.facts().experienceClauses(), candidate.uncertainties(), sourceForms);
        JobIntelligence.Facts facts = new JobIntelligence.Facts(
                List.copyOf(candidate.facts().skills()),
                List.copyOf(candidate.facts().experienceClauses()),
                List.copyOf(candidate.facts().qualifications()));
        JobIntelligence.Interpretations interp = new JobIntelligence.Interpretations(
                candidate.interpretations().roleFamily(),
                candidate.interpretations().seniority(),
                List.copyOf(candidate.interpretations().responsibilities()),
                List.copyOf(candidate.interpretations().technicalConcepts()));
        return new Result.Success(new JobIntelligence(facts, interp, List.copyOf(candidate.evidence()),
                List.copyOf(candidate.uncertainties()), minimum));
    }

    private Result references(JobIntelligenceModel.Output candidate, Map<String, Evidence> evidenceById) {
        Result skills = idList("facts.skills", candidate.facts().skills().stream().map(Skill::evidenceIds).toList(), evidenceById);
        if (skills instanceof Result.Failure failure) return skills;
        Result clauses = idList("facts.experienceClauses", candidate.facts().experienceClauses().stream().map(ExperienceClause::evidenceIds).toList(), evidenceById);
        if (clauses instanceof Result.Failure failure) return clauses;
        Result qualifications = idList("facts.qualifications", candidate.facts().qualifications().stream().map(Qualification::evidenceIds).toList(), evidenceById);
        if (qualifications instanceof Result.Failure failure) return qualifications;
        if (candidate.interpretations().roleFamily() != null) {
            Result role = ids("interpretations.roleFamily.evidenceIds", candidate.interpretations().roleFamily().evidenceIds(), evidenceById);
            if (role instanceof Result.Failure failure) return role;
        }
        if (candidate.interpretations().seniority() != null) {
            Result seniority = ids("interpretations.seniority.evidenceIds", candidate.interpretations().seniority().evidenceIds(), evidenceById);
            if (seniority instanceof Result.Failure failure) return seniority;
        }
        Result responsibilities = idList("interpretations.responsibilities", candidate.interpretations().responsibilities().stream().map(TextClaim::evidenceIds).toList(), evidenceById);
        if (responsibilities instanceof Result.Failure failure) return responsibilities;
        Result concepts = idList("interpretations.technicalConcepts", candidate.interpretations().technicalConcepts().stream().map(TextClaim::evidenceIds).toList(), evidenceById);
        if (concepts instanceof Result.Failure failure) return concepts;
        return idList("uncertainties", candidate.uncertainties().stream().map(Uncertainty::evidenceIds).toList(), evidenceById);
    }

    private Result skills(List<Skill> skills, Map<String, Evidence> evidenceById, Map<String, List<String>> sourceContexts) {
        for (int i = 0; i < skills.size(); i++) {
            Skill skill = skills.get(i);
            String path = "facts.skills[" + i + "]";
            List<String> quotes = quotes(skill.evidenceIds(), evidenceById);
            if (!namedIn(quotes, skill.name())) {
                return new Result.Failure(Code.FACT_NOT_GROUNDED, Location.of(path + ".name"));
            }
            for (String supporting : contexts(skill.evidenceIds(), sourceContexts)) {
                if (!TechnologyTokens.contains(supporting, skill.name()) || unsupportedModifier(supporting))
                    return new Result.Failure(Code.UNSUPPORTED_CLAIM_FORM, Location.of(path));
                if (skill.importance() == Importance.REQUIRED) {
                    String around = window(supporting, skill.name());
                    if (!REQUIRED_MARKER.matcher(around).find() || negated(supporting, skill.name())
                            || PREFERRED_MARKER.matcher(supporting).find()
                            || Pattern.compile("(?i)\\ba plus\\b").matcher(supporting).find()) {
                        return new Result.Failure(Code.UNSUPPORTED_CLAIM_FORM, Location.of(path + ".importance"));
                    }
                } else if (skill.importance() == Importance.PREFERRED) {
                    if (!PREFERRED_MARKER.matcher(window(supporting, skill.name())).find() || negated(supporting, skill.name())) {
                        return new Result.Failure(Code.UNSUPPORTED_CLAIM_FORM, Location.of(path + ".importance"));
                    }
                } else if (negated(supporting, skill.name())) {
                    return new Result.Failure(Code.UNSUPPORTED_CLAIM_FORM, Location.of(path));
                }
            }
        }
        return new Result.Success(null);
    }

    private Result experience(List<ExperienceClause> clauses, Map<String, Evidence> evidenceById,
            Map<String, List<String>> sourceContexts, List<ExperienceForm> sourceForms) {
        for (int i = 0; i < clauses.size(); i++) {
            ExperienceClause clause = clauses.get(i);
            String path = "facts.experienceClauses[" + i + "]";
            if ((clause.minimum() == null) != (clause.unit() == null)) {
                return new Result.Failure(Code.INCONSISTENT_EXPERIENCE, Location.of(path));
            }
            List<String> quotes = quotes(clause.evidenceIds(), evidenceById);
            if (!quotedAsSubstring(quotes, clause.text())) {
                return new Result.Failure(Code.FACT_NOT_GROUNDED, Location.of(path + ".text"));
            }
            if (ExperienceForm.parse(clause.text()) == null)
                return new Result.Failure(Code.UNSUPPORTED_CLAIM_FORM, Location.of(path + ".text"));
            ExperienceForm first = null;
            for (String supporting : contexts(clause.evidenceIds(), sourceContexts)) {
                var supported = ExperienceSupport.from(supporting);
                if (supported == null || NEGATION.matcher(supporting).find())
                    return new Result.Failure(Code.UNSUPPORTED_CLAIM_FORM, Location.of(path));
                if (supported.scope() != clause.scope() || supported.conditional() != clause.conditional())
                    return new Result.Failure(Code.INCONSISTENT_EXPERIENCE, Location.of(path));
                ExperienceForm form = supported.form();
                if (first != null && !first.sameQuantity(form))
                    return new Result.Failure(Code.INCONSISTENT_EXPERIENCE, Location.of(path));
                first = form;
                if (form.upper() != null && form.lower().compareTo(form.upper()) > 0)
                    return new Result.Failure(Code.INCONSISTENT_EXPERIENCE, Location.of(path + ".text"));
                if (form.exclusive()) {
                    if (clause.minimum() != null && clause.minimum().compareTo(form.lower()) != 0) {
                        return new Result.Failure(Code.INCONSISTENT_EXPERIENCE, Location.of(path + ".minimum"));
                    }
                    if (clause.unit() != null && clause.unit() != form.unit()) {
                        return new Result.Failure(Code.INCONSISTENT_EXPERIENCE, Location.of(path + ".unit"));
                    }
                } else if (form.lower() != null) {
                    if (clause.minimum() == null || clause.minimum().compareTo(form.lower()) != 0 || clause.unit() != form.unit()) {
                        return new Result.Failure(Code.INCONSISTENT_EXPERIENCE, Location.of(path));
                    }
                }
                if (clause.importance() == Importance.REQUIRED) {
                    boolean requiredLanguage = REQUIRED_MARKER.matcher(supporting).find() || ExperienceForm.impliesRequired(supporting);
                    if (!requiredLanguage || negatedExperience(supporting) || PREFERRED_MARKER.matcher(supporting).find()) {
                        return new Result.Failure(Code.UNSUPPORTED_CLAIM_FORM, Location.of(path + ".importance"));
                    }
                } else if (clause.importance() == Importance.PREFERRED && !PREFERRED_MARKER.matcher(supporting).find()) {
                    return new Result.Failure(Code.UNSUPPORTED_CLAIM_FORM, Location.of(path + ".importance"));
                }
            }
            sourceForms.add(first);
        }
        return new Result.Success(null);
    }

    private Result qualifications(List<Qualification> qualifications, Map<String, Evidence> evidenceById, Map<String, List<String>> sourceContexts) {
        for (int i = 0; i < qualifications.size(); i++) {
            Qualification qualification = qualifications.get(i);
            String path = "facts.qualifications[" + i + "]";
            List<String> quotes = quotes(qualification.evidenceIds(), evidenceById);
            if (!quotedAsSubstring(quotes, qualification.text())) {
                return new Result.Failure(Code.FACT_NOT_GROUNDED, Location.of(path + ".text"));
            }
            for (String supporting : contexts(qualification.evidenceIds(), sourceContexts)) {
                if (!supporting.contains(qualification.text()) || unsupportedModifier(supporting))
                    return new Result.Failure(Code.UNSUPPORTED_CLAIM_FORM, Location.of(path));
                if (qualification.importance() == Importance.REQUIRED) {
                    if (!REQUIRED_MARKER.matcher(window(supporting, qualification.text())).find()
                            || PREFERRED_MARKER.matcher(supporting).find()
                            || NEGATION.matcher(window(supporting, qualification.text())).find()) {
                        return new Result.Failure(Code.UNSUPPORTED_CLAIM_FORM, Location.of(path + ".importance"));
                    }
                } else if (qualification.importance() == Importance.PREFERRED) {
                    if (!PREFERRED_MARKER.matcher(supporting).find()) {
                        return new Result.Failure(Code.UNSUPPORTED_CLAIM_FORM, Location.of(path + ".importance"));
                    }
                }
            }
        }
        return new Result.Success(null);
    }

    private Result interpretations(JobIntelligenceModel.Output candidate, Map<String, Evidence> evidenceById, Map<String, List<String>> sourceContexts) {
        if (candidate.interpretations().roleFamily() != null) {
            List<String> quotes = contexts(candidate.interpretations().roleFamily().evidenceIds(), sourceContexts);
            if (quotes.stream().anyMatch(q -> unsupportedModifier(q)
                    || !InterpretationSupport.role(candidate.interpretations().roleFamily().value(), List.of(q)))) {
                return new Result.Failure(Code.UNSUPPORTED_INTERPRETATION, Location.of("interpretations.roleFamily"));
            }
        }
        if (candidate.interpretations().seniority() != null) {
            List<String> quotes = contexts(candidate.interpretations().seniority().evidenceIds(), sourceContexts);
            if (quotes.stream().anyMatch(q -> unsupportedModifier(q)
                    || !InterpretationSupport.seniority(candidate.interpretations().seniority().value(), List.of(q)))) {
                return new Result.Failure(Code.UNSUPPORTED_INTERPRETATION, Location.of("interpretations.seniority"));
            }
        }
        Result responsibilities = textClaims("interpretations.responsibilities", candidate.interpretations().responsibilities(), evidenceById, sourceContexts);
        if (responsibilities instanceof Result.Failure failure) return responsibilities;
        return textClaims("interpretations.technicalConcepts", candidate.interpretations().technicalConcepts(), evidenceById, sourceContexts);
    }

    private Result textClaims(String prefix, List<TextClaim> claims, Map<String, Evidence> evidenceById, Map<String, List<String>> sourceContexts) {
        for (int i = 0; i < claims.size(); i++) {
            TextClaim claim = claims.get(i);
            if (!quotedAsSubstring(quotes(claim.evidenceIds(), evidenceById), claim.text())
                    || contexts(claim.evidenceIds(), sourceContexts).stream().anyMatch(q -> !q.contains(claim.text()) || unsupportedModifier(q))) {
                return new Result.Failure(Code.UNSUPPORTED_INTERPRETATION, Location.of(prefix + "[" + i + "].text"));
            }
        }
        return new Result.Success(null);
    }

    private Result uncertainties(List<Uncertainty> uncertainties, Map<String, Evidence> evidenceById, JobIntelligenceModel.Output candidate) {
        boolean experienceUncertainty = uncertainties.stream().anyMatch(u -> u.code() == UncertaintyCode.AMBIGUOUS_EXPERIENCE);
        if (experienceUncertainty) return new Result.Success(null);
        return new Result.Success(null);
    }

    private Result idList(String prefix, List<List<String>> groups, Map<String, Evidence> evidenceById) {
        for (int i = 0; i < groups.size(); i++) {
            Result result = ids(prefix + "[" + i + "].evidenceIds", groups.get(i), evidenceById);
            if (result instanceof Result.Failure failure) return failure;
        }
        return new Result.Success(null);
    }

    private Result ids(String path, List<String> ids, Map<String, Evidence> evidenceById) {
        for (int i = 0; i < ids.size(); i++) {
            if (!evidenceById.containsKey(ids.get(i))) {
                return new Result.Failure(Code.DANGLING_EVIDENCE_ID, Location.of(path + "[" + i + "]"));
            }
        }
        return new Result.Success(null);
    }

    private static List<String> quotes(List<String> ids, Map<String, Evidence> evidenceById) {
        List<String> quotes = new ArrayList<>();
        for (String id : ids) quotes.add(evidenceById.get(id).quote());
        return quotes;
    }

    private static List<String> contexts(List<String> ids, Map<String, List<String>> contexts) {
        return ids.stream().flatMap(id -> contexts.get(id).stream()).toList();
    }

    private static boolean unsupportedModifier(String context) {
        return NEGATION.matcher(context).find() || ALTERNATIVE.matcher(context).find();
    }

    private static boolean containsExact(String source, String quote) {
        return quote != null && !quote.isEmpty() && source.contains(quote);
    }

    private static boolean quotedAsSubstring(List<String> quotes, String text) {
        for (String quote : quotes) if (quote.contains(text)) return true;
        return false;
    }

    private static boolean namedIn(List<String> quotes, String name) {
        for (String quote : quotes) if (TechnologyTokens.contains(quote, name)) return true;
        return false;
    }

    private static boolean negated(String quote, String name) {
        return NEGATION.matcher(window(quote, name)).find();
    }

    private static boolean negatedExperience(String quote) {
        return Pattern.compile("(?i)\\b(?:no|not|without|optional)\\b.{0,24}\\b(?:experience|years?|months?)\\b").matcher(quote).find();
    }

    private static String window(String quote, String span) {
        int start = quote.indexOf(span);
        if (start < 0) return quote;
        int from = Math.max(0, start - 48);
        int to = Math.min(quote.length(), start + span.length() + 32);
        // Markers in another sentence/list clause cannot establish this claim's importance.
        for (int i = start - 1; i >= from; i--) {
            if (separator(quote, i)) { from = i + 1; break; }
        }
        for (int i = start + span.length(); i < to; i++) {
            if (separator(quote, i)) { to = i; break; }
        }
        return quote.substring(from, to);
    }

    private static boolean separator(String text, int i) {
        char c = text.charAt(i);
        return c == ';' || c == ',' || c == '\n' || c == '\r' || c == '!' || c == '?'
                || c == '.' && (i + 1 == text.length() || Character.isWhitespace(text.charAt(i + 1)));
    }

    static BigDecimal months(BigDecimal minimum, Unit unit) {
        if (minimum == null || unit == null) return null;
        return unit == Unit.YEARS ? minimum.multiply(TWELVE) : minimum;
    }
}
