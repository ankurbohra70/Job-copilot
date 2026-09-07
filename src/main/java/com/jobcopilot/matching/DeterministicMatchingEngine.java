package com.jobcopilot.matching;

import com.jobcopilot.common.text.MatchingVocabulary;
import com.jobcopilot.job.JobMatchingSnapshot;
import com.jobcopilot.resume.CandidateMatchingSnapshot;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import java.math.*;
import java.util.*;
import static com.jobcopilot.matching.MatchResult.*;

@Component
public class DeterministicMatchingEngine {
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal TWELVE = BigDecimal.valueOf(12);
    private static final MathContext MC = MathContext.DECIMAL128;
    private final MatchingPolicy policy;
    private final MatchingVocabulary vocabulary = MatchingVocabulary.standard();
    @Autowired public DeterministicMatchingEngine() { this(MatchingPolicy.v1()); }
    public DeterministicMatchingEngine(MatchingPolicy policy) { this.policy = policy; }
    public String version() { return policy.version(); }

    public MatchResult match(JobMatchingSnapshot job, CandidateMatchingSnapshot candidate) {
        var requirements = job.requirements();
        var required = canonical(requirements.requiredSkills());
        var preferred = canonical(requirements.preferredSkills()).stream().filter(s -> !required.contains(s)).toList();
        BigDecimal minimum = requirements.minYearsExperience();
        boolean hasSkills = !required.isEmpty() || !preferred.isEmpty();
        boolean hasExperience = minimum != null && minimum.signum() > 0;
        if (!hasSkills && !hasExperience) throw new MatchCannotBeComputedException();
        // Evidence is resolved once. The same sets drive both score and explanations.
        Set<String> recognized = new TreeSet<>(canonical(candidate.profile().skills()));
        List<String> requiredMatched = required.stream().filter(s -> recognized.contains(s) || vocabulary.hasSkill(candidate.extractedText(), s)).toList();
        List<String> preferredMatched = preferred.stream().filter(s -> recognized.contains(s) || vocabulary.hasSkill(candidate.extractedText(), s)).toList();
        List<String> missing = required.stream().filter(s -> !requiredMatched.contains(s)).toList();
        List<String> unmatched = preferred.stream().filter(s -> !preferredMatched.contains(s)).toList();
        Map<Category, BigDecimal> scores = new EnumMap<>(Category.class);
        Map<Category, Status> statuses = new EnumMap<>(Category.class);
        List<Explanation> strengths = new ArrayList<>(), gaps = new ArrayList<>();
        List<String> warnings = new ArrayList<>(candidate.profile().warnings());
        if (hasSkills) {
            BigDecimal r = ratio(requiredMatched.size(), required.size()), p = ratio(preferredMatched.size(), preferred.size());
            scores.put(Category.SKILLS, required.isEmpty() ? p : preferred.isEmpty() ? r :
                    r.multiply(policy.requiredShare()).add(p.multiply(BigDecimal.ONE.subtract(policy.requiredShare()))));
            statuses.put(Category.SKILLS, recognized.isEmpty() && requiredMatched.isEmpty() && preferredMatched.isEmpty() ? Status.UNKNOWN : Status.ASSESSED);
        }
        for (String skill : requiredMatched) strengths.add(skillEvidence("REQUIRED_SKILL_MATCH", skill, candidate));
        for (String skill : preferredMatched) strengths.add(skillEvidence("PREFERRED_SKILL_MATCH", skill, candidate));
        for (String skill : missing) gaps.add(new Explanation("MISSING_REQUIRED_SKILL", "No evidence recognized for required skill: " + skill, List.of()));
        for (String skill : unmatched) gaps.add(new Explanation("UNMATCHED_PREFERRED_SKILL", "No evidence recognized for preferred skill: " + skill, List.of()));
        Integer months = candidate.profile().totalExperienceMonths();
        BigDecimal candidateYears = months == null ? null : BigDecimal.valueOf(months).divide(TWELVE, 2, RoundingMode.HALF_UP);
        Status experienceStatus = Status.NOT_APPLICABLE;
        BigDecimal experienceScore = null;
        if (hasExperience) {
            experienceScore = months == null ? BigDecimal.ZERO :
                    BigDecimal.valueOf(months).divide(minimum.multiply(TWELVE), MC).min(BigDecimal.ONE);
            experienceStatus = months == null ? Status.UNKNOWN :
                    experienceScore.compareTo(BigDecimal.ONE) >= 0 ? Status.MEETS_REQUIREMENT : Status.BELOW_REQUIREMENT;
            scores.put(Category.EXPERIENCE, experienceScore); statuses.put(Category.EXPERIENCE, experienceStatus);
            var explanation = new Explanation("EXPERIENCE_" + experienceStatus, months == null
                    ? "Total experience is unknown; no experience credit awarded"
                    : "Recognized " + months + " months against " + minimum + " required years",
                    candidate.profile().workExperience().stream().map(e -> e.sourceText()).toList());
            if (experienceStatus == Status.MEETS_REQUIREMENT) strengths.add(explanation); else gaps.add(explanation);
        }
        List<String> jobRoles = vocabulary.roles(job.title());
        List<String> candidateRoles = candidate.profile().roleCategories();
        List<String> matchedRoles = jobRoles.stream().filter(candidateRoles::contains).toList();
        Relevance role;
        if (jobRoles.isEmpty()) role = new Relevance(Status.NOT_APPLICABLE, null, List.of());
        else {
            BigDecimal value = matchedRoles.isEmpty() ? BigDecimal.ZERO : BigDecimal.ONE;
            Status status = candidateRoles.isEmpty() ? Status.UNKNOWN : Status.ASSESSED;
            scores.put(Category.ROLE, value); statuses.put(Category.ROLE, status);
            role = new Relevance(status, percent(value), matchedRoles);
        }
        List<String> jobKeywords = vocabulary.keywords(job.description());
        List<String> candidateKeywords = vocabulary.keywords(candidate.extractedText());
        List<String> matchedKeywords = jobKeywords.stream().filter(candidateKeywords::contains).toList();
        Relevance keywords;
        if (jobKeywords.isEmpty()) keywords = new Relevance(Status.NOT_APPLICABLE, null, List.of());
        else {
            BigDecimal value = ratio(matchedKeywords.size(), jobKeywords.size());
            Status status = candidateKeywords.isEmpty() ? Status.UNKNOWN : Status.ASSESSED;
            scores.put(Category.KEYWORDS, value); statuses.put(Category.KEYWORDS, status);
            keywords = new Relevance(status, percent(value), matchedKeywords);
        }
        explainRelevance("ROLE", role, strengths, gaps);
        explainRelevance("KEYWORDS", keywords, strengths, gaps);
        int totalWeight = scores.keySet().stream().mapToInt(c -> policy.weights().get(c)).sum();
        BigDecimal raw = BigDecimal.ZERO;
        List<Breakdown> breakdown = new ArrayList<>();
        for (Category category : Category.values()) {
            if (!scores.containsKey(category)) {
                breakdown.add(new Breakdown(category, Status.NOT_APPLICABLE, null, BigDecimal.ZERO, BigDecimal.ZERO)); continue;
            }
            BigDecimal effective = BigDecimal.valueOf(policy.weights().get(category)).divide(BigDecimal.valueOf(totalWeight), MC);
            BigDecimal contribution = scores.get(category).multiply(effective, MC).multiply(HUNDRED);
            raw = raw.add(contribution);
            breakdown.add(new Breakdown(category, statuses.get(category), percent(scores.get(category)), effective.setScale(6, RoundingMode.HALF_UP),
                    contribution.setScale(6, RoundingMode.HALF_UP)));
        }
        List<Cap> caps = new ArrayList<>();
        BigDecimal cap = HUNDRED;
        if (!missing.isEmpty()) { cap = policy.missingRequiredCap(); caps.add(new Cap("MISSING_REQUIRED_SKILL", cap)); }
        if (!required.isEmpty() && requiredMatched.isEmpty()) {
            cap = cap.min(policy.noneRequiredCap()); caps.add(new Cap("NO_REQUIRED_SKILL_MATCH", policy.noneRequiredCap()));
        }
        BigDecimal finalScore = raw.min(cap).setScale(2, RoundingMode.HALF_UP);
        if (!candidate.vocabularyVersion().equals(vocabulary.version())) warnings.add("Profile vocabulary differs from scoring vocabulary");
        return new MatchResult(finalScore, policy.recommendation(finalScore), requiredMatched, missing, preferredMatched, unmatched,
                new ExperienceComparison(minimum, candidateYears, experienceStatus, experienceScore == null ? null : percent(experienceScore)),
                role, keywords, breakdown, caps, strengths, gaps, warnings, List.of("LOCATION_WORK_MODE"));
    }
    private List<String> canonical(List<String> values) { return values.stream().map(vocabulary::canonical).distinct().sorted().toList(); }
    private static BigDecimal ratio(int numerator, int denominator) {
        return denominator == 0 ? BigDecimal.ZERO : BigDecimal.valueOf(numerator).divide(BigDecimal.valueOf(denominator), MC);
    }
    private static BigDecimal percent(BigDecimal ratio) { return ratio.multiply(HUNDRED).setScale(2, RoundingMode.HALF_UP); }
    private Explanation skillEvidence(String code, String skill, CandidateMatchingSnapshot candidate) {
        List<String> sources = candidate.extractedText().lines().filter(line -> vocabulary.hasSkill(line, skill)).limit(3).toList();
        if (sources.isEmpty()) sources = candidate.profile().evidence().stream().filter(e -> e.value().equals(skill)).map(e -> e.sourceText()).toList();
        return new Explanation(code, "Recognized evidence for " + skill, sources);
    }
    private static void explainRelevance(String category, Relevance relevance, List<Explanation> strengths, List<Explanation> gaps) {
        if (relevance.status() == Status.NOT_APPLICABLE) return;
        if (!relevance.matchedTerms().isEmpty())
            strengths.add(new Explanation(category + "_OVERLAP", "Recognized " + category.toLowerCase(Locale.ROOT) + " overlap", relevance.matchedTerms()));
        else gaps.add(new Explanation(category + "_NO_EVIDENCE", "No " + category.toLowerCase(Locale.ROOT) + " overlap recognized", List.of()));
    }
}

