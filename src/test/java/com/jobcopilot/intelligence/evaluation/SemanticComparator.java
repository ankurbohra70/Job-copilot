package com.jobcopilot.intelligence.evaluation;

import com.jobcopilot.common.text.MatchingVocabulary;
import com.jobcopilot.intelligence.JobIntelligence;
import com.jobcopilot.intelligence.JobIntelligenceResult;
import com.jobcopilot.job.JobExtractionBaseline;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import static com.jobcopilot.intelligence.evaluation.EvaluationResult.*;

final class SemanticComparator {
    static final String MODE = "FIXTURE_BACKED";
    private static final BigDecimal TWELVE = new BigDecimal("12");
    private final MatchingVocabulary vocabulary = MatchingVocabulary.standard();
    private final String datasetVersion;
    private final String responseVersion;

    SemanticComparator(String datasetVersion, String responseVersion) {
        this.datasetVersion = datasetVersion;
        this.responseVersion = responseVersion;
    }

    EvaluatedCase evaluate(EvaluationCaseExecution execution) {
        EvaluationCase value = execution.evaluationCase();
        List<String> tags = value.tags().stream().sorted().toList();
        return new EvaluatedCase(value.id(), tags,
                evaluate(value, Strategy.JC005, execution.baseline()),
                evaluate(value, Strategy.HYBRID_ENRICHMENT, execution.hybrid()),
                evaluate(value, Strategy.LLM_FIRST, execution.llmFirst()));
    }

    private StrategyEvaluation evaluate(EvaluationCase value, Strategy strategy, RawExecution raw) {
        TerminalStatus status = terminal(raw);
        FailureDescriptor failure = failure(raw);
        List<PredictedClaim> skills = new ArrayList<>();
        List<PredictedQualification> qualifications = new ArrayList<>();
        List<JobIntelligence.ExperienceClause> experience = new ArrayList<>();
        List<JobIntelligence.Evidence> evidence = List.of();
        JobIntelligence.MinimumExperience minimum = null;
        boolean acceptedAbstention = false;

        if (raw instanceof BaselineExecution baselineExecution
                && baselineExecution.result().status() == JobExtractionBaseline.Status.SUCCESS) {
            var requirements = baselineExecution.result().requirements();
            requirements.requiredSkills().forEach(name -> skills.add(new PredictedClaim(name,
                    JobIntelligence.Importance.REQUIRED, List.of())));
            requirements.preferredSkills().forEach(name -> skills.add(new PredictedClaim(name,
                    JobIntelligence.Importance.PREFERRED, List.of())));
            minimum = requirements.minYearsExperience() == null
                    ? new JobIntelligence.MinimumExperience(JobIntelligence.MinimumStatus.NOT_STATED, null)
                    : new JobIntelligence.MinimumExperience(JobIntelligence.MinimumStatus.KNOWN,
                            requirements.minYearsExperience().multiply(TWELVE));
        } else if (raw instanceof IntelligenceExecution intelligenceExecution
                && intelligenceExecution.result() instanceof JobIntelligenceResult.Accepted accepted) {
            JobIntelligence intelligence = accepted.intelligence();
            intelligence.facts().skills().forEach(skill -> skills.add(new PredictedClaim(
                    skill.name(), skill.importance(), skill.evidenceIds())));
            intelligence.facts().qualifications().forEach(qualification -> qualifications.add(
                    new PredictedQualification(qualification.text(), qualification.importance(),
                            qualification.evidenceIds())));
            experience.addAll(intelligence.facts().experienceClauses());
            evidence = intelligence.evidence();
            minimum = intelligence.minimumExperience();
            acceptedAbstention = intelligence.facts().skills().isEmpty()
                    && intelligence.facts().qualifications().isEmpty()
                    && intelligence.facts().experienceClauses().isEmpty()
                    && !intelligence.uncertainties().isEmpty();
        }

        ClaimBreakdown skillResult = compareSkills(value, skills, evidence, strategy != Strategy.JC005);
        ClaimBreakdown qualificationResult = strategy == Strategy.JC005 ? null
                : compareQualifications(value, qualifications, evidence);
        ExperienceBreakdown experienceResult = strategy == Strategy.JC005
                ? new ExperienceBreakdown(false, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
                : compareExperience(value, experience, evidence);
        MinimumBreakdown minimumResult = compareMinimum(value.gold().minimumExperience(), minimum);
        boolean correctAbstention = acceptedAbstention && !value.gold().hasAffirmativeFacts();
        boolean missedGold = acceptedAbstention && value.gold().hasAffirmativeFacts();
        return new StrategyEvaluation(value.id(), value.tags().stream().sorted().toList(), strategy, status, failure,
                new SemanticBreakdown(skillResult, qualificationResult, experienceResult, minimumResult),
                acceptedAbstention, correctAbstention, missedGold, metadata(strategy, raw));
    }

    private ClaimBreakdown compareSkills(EvaluationCase value, List<PredictedClaim> predicted,
            List<JobIntelligence.Evidence> evidence, boolean compareEvidence) {
        List<GoldClaim> gold = value.gold().skills().stream().map(skill -> new GoldClaim(
                skillKey(skill.name()), skill.name(), skill.importance(), skill.support())).toList();
        List<ComparableClaim> actual = predicted.stream().map(claim -> new ComparableClaim(
                skillKey(claim.text()), claim.text(), claim.importance(), claim.evidenceIds())).toList();
        return compareClaims(value, gold, actual, evidence, compareEvidence);
    }

    private ClaimBreakdown compareQualifications(EvaluationCase value, List<PredictedQualification> predicted,
            List<JobIntelligence.Evidence> evidence) {
        Map<String, GoldQualification> accepted = new HashMap<>();
        List<GoldClaim> gold = new ArrayList<>();
        for (GoldQualification qualification : value.gold().qualifications()) {
            gold.add(new GoldClaim(qualification.id(), qualification.id(), qualification.importance(), qualification.support()));
            for (String text : qualification.acceptedTexts()) accepted.put(normalize(text), qualification);
        }
        List<ComparableClaim> actual = new ArrayList<>();
        for (PredictedQualification prediction : predicted) {
            GoldQualification match = accepted.get(normalize(prediction.text()));
            String key = match == null ? "unmatched:" + normalize(prediction.text()) : match.id();
            actual.add(new ComparableClaim(key, prediction.text(), prediction.importance(), prediction.evidenceIds()));
        }
        return compareClaims(value, gold, actual, evidence, true);
    }

    private ClaimBreakdown compareClaims(EvaluationCase value, List<GoldClaim> gold, List<ComparableClaim> predictions,
            List<JobIntelligence.Evidence> evidence, boolean compareEvidence) {
        EnumMap<JobIntelligence.Importance, Counts> counts = new EnumMap<>(JobIntelligence.Importance.class);
        List<String> fps = new ArrayList<>(), fns = new ArrayList<>();
        int duplicates = 0;
        Set<String> unique = new HashSet<>();
        Map<String, Set<JobIntelligence.Importance>> predictedClasses = new HashMap<>();
        for (ComparableClaim prediction : predictions) {
            String classKey = prediction.key() + "\u0000" + prediction.importance();
            if (!unique.add(classKey)) duplicates++;
            predictedClasses.computeIfAbsent(prediction.key(), ignored -> new HashSet<>()).add(prediction.importance());
        }
        Map<String, ComparableClaim> uniquePredictions = new LinkedHashMap<>();
        predictions.stream().sorted(Comparator.comparing(ComparableClaim::key)
                        .thenComparing(p -> p.importance().name()))
                .forEach(p -> uniquePredictions.putIfAbsent(p.key() + "\u0000" + p.importance(), p));
        int evidenceMismatches = 0;
        for (JobIntelligence.Importance importance : JobIntelligence.Importance.values()) {
            Set<String> expected = new HashSet<>();
            for (GoldClaim claim : gold) if (claim.importance() == importance) expected.add(claim.key());
            Set<String> actual = new HashSet<>();
            for (ComparableClaim claim : uniquePredictions.values()) if (claim.importance() == importance) actual.add(claim.key());
            Set<String> tp = intersection(expected, actual);
            Set<String> fp = difference(actual, expected);
            Set<String> fn = difference(expected, actual);
            counts.put(importance, new Counts(tp.size(), fp.size(), fn.size()));
            fp.stream().sorted().map(key -> importance + ":" + displayPrediction(key, uniquePredictions, importance)).forEach(fps::add);
            fn.stream().sorted().map(key -> importance + ":" + displayGold(key, gold)).forEach(fns::add);
            if (compareEvidence) {
                for (String key : tp) {
                    GoldClaim expectedClaim = gold.stream().filter(g -> g.key().equals(key)
                            && g.importance() == importance).findFirst().orElseThrow();
                    ComparableClaim prediction = uniquePredictions.get(key + "\u0000" + importance);
                    if (!evidenceMatches(value, prediction.evidenceIds(), evidence, expectedClaim.support())) evidenceMismatches++;
                }
            }
        }
        Set<String> goldRequired = keys(gold, JobIntelligence.Importance.REQUIRED);
        Set<String> goldPreferred = keys(gold, JobIntelligence.Importance.PREFERRED);
        Set<String> predictedRequired = keysPredicted(uniquePredictions.values(), JobIntelligence.Importance.REQUIRED);
        Set<String> predictedPreferred = keysPredicted(uniquePredictions.values(), JobIntelligence.Importance.PREFERRED);
        int requiredToPreferred = difference(intersection(goldRequired, predictedPreferred), predictedRequired).size();
        int preferredToRequired = difference(intersection(goldPreferred, predictedRequired), predictedPreferred).size();
        fps.sort(String::compareTo); fns.sort(String::compareTo);
        int conflicts = (int) predictedClasses.values().stream().filter(classes -> classes.size() > 1).count();
        return new ClaimBreakdown(counts, requiredToPreferred, preferredToRequired, duplicates,
                conflicts, evidenceMismatches, fps, fns);
    }

    private ExperienceBreakdown compareExperience(EvaluationCase value,
            List<JobIntelligence.ExperienceClause> predictions, List<JobIntelligence.Evidence> evidence) {
        List<GoldExperienceClause> gold = value.gold().experienceClauses().stream()
                .sorted(Comparator.comparing(GoldExperienceClause::id)).toList();
        List<JobIntelligence.ExperienceClause> predicted = predictions.stream()
                .sorted(Comparator.comparing(SemanticComparator::experienceKey)).toList();
        Set<String> used = new HashSet<>();
        int detected = 0, extra = 0, exact = 0, quantity = 0, importance = 0, scope = 0, conditional = 0, anchor = 0;
        for (JobIntelligence.ExperienceClause prediction : predicted) {
            GoldExperienceClause match = gold.stream().filter(g -> !used.contains(g.id())
                    && evidenceMatches(value, prediction.evidenceIds(), evidence, g.support())).findFirst().orElse(null);
            boolean evidenceMatched = match != null;
            if (match == null) {
                match = gold.stream().filter(g -> !used.contains(g.id()) && sameSemanticQuantity(g, prediction)
                        && g.importance() == prediction.importance() && g.scope() == prediction.scope()
                        && g.conditional() == prediction.conditional()).findFirst().orElse(null);
            }
            if (match == null) { extra++; continue; }
            used.add(match.id());
            detected++;
            boolean quantitySame = sameSemanticQuantity(match, prediction);
            boolean importanceSame = match.importance() == prediction.importance();
            boolean scopeSame = match.scope() == prediction.scope();
            boolean conditionalSame = match.conditional() == prediction.conditional();
            if (!quantitySame) quantity++;
            if (!importanceSame) importance++;
            if (!scopeSame) scope++;
            if (!conditionalSame) conditional++;
            if (!evidenceMatched) anchor++;
            if (quantitySame && importanceSame && scopeSame && conditionalSame && evidenceMatched) exact++;
        }
        int missed = gold.size() - used.size();
        return new ExperienceBreakdown(true, gold.size(), detected, missed, extra, exact, quantity,
                importance, scope, conditional, anchor);
    }

    private static boolean sameSemanticQuantity(GoldExperienceClause gold, JobIntelligence.ExperienceClause prediction) {
        return numericEquals(months(gold.minimum(), gold.unit()), months(prediction.minimum(), prediction.unit()));
    }

    private static String experienceKey(JobIntelligence.ExperienceClause value) {
        return value.importance() + "|" + value.scope() + "|" + value.conditional() + "|"
                + (months(value.minimum(), value.unit()) == null ? "null" : months(value.minimum(), value.unit()).toPlainString())
                + "|" + normalize(value.text());
    }

    private static MinimumBreakdown compareMinimum(GoldMinimumExperience gold, JobIntelligence.MinimumExperience predicted) {
        if (predicted == null) return new MinimumBreakdown(true, false, false, false, true);
        boolean status = gold.status() != predicted.status();
        boolean numeric = !status && gold.status() == JobIntelligence.MinimumStatus.KNOWN
                && !numericEquals(gold.months(), predicted.months());
        return new MinimumBreakdown(true, !status && !numeric, status, numeric, false);
    }

    private ReproducibilityMetadata metadata(Strategy strategy, RawExecution raw) {
        String provider = strategy == Strategy.JC005 ? "jc005" : null;
        String requested = null, returned = null, prompt = null, schema = null, validation = null;
        if (raw instanceof IntelligenceExecution execution) {
            var value = execution.result().metadata();
            provider = value.providerId(); requested = value.requestedModel(); returned = value.returnedModel();
            prompt = value.promptIdentity(); schema = value.schemaIdentity(); validation = value.validationPolicyVersion();
        }
        return new ReproducibilityMetadata(MODE, datasetVersion, responseVersion, vocabulary.version(),
                strategy.name(), provider, requested, returned, prompt, schema, validation);
    }

    private static TerminalStatus terminal(RawExecution raw) {
        if (raw instanceof DefectExecution) return TerminalStatus.EXECUTION_DEFECT;
        if (raw instanceof BaselineExecution baseline) return baseline.result().status() == JobExtractionBaseline.Status.SUCCESS
                ? TerminalStatus.SUCCEEDED : TerminalStatus.BASELINE_UNAVAILABLE;
        JobIntelligenceResult result = ((IntelligenceExecution) raw).result();
        if (result instanceof JobIntelligenceResult.Accepted) return TerminalStatus.SUCCEEDED;
        JobIntelligenceResult.Failed failed = (JobIntelligenceResult.Failed) result;
        return switch (failed.failure().stage()) {
            case PREFLIGHT -> TerminalStatus.PREFLIGHT_FAILURE;
            case DECODE -> TerminalStatus.DECODE_FAILURE;
            case VALIDATION -> TerminalStatus.VALIDATION_FAILURE;
            case EXECUTION -> providerFailure(failed.failure().code())
                    ? TerminalStatus.PROVIDER_FAILURE : TerminalStatus.EXECUTION_FAILURE;
        };
    }

    private static FailureDescriptor failure(RawExecution raw) {
        if (raw instanceof DefectExecution defect) return new FailureDescriptor(null, null, null, defect.exceptionType());
        if (raw instanceof BaselineExecution baseline && baseline.result().status() == JobExtractionBaseline.Status.UNAVAILABLE)
            return new FailureDescriptor(null, baseline.result().failureCode().name(), null, null);
        if (raw instanceof IntelligenceExecution execution && execution.result() instanceof JobIntelligenceResult.Failed failed)
            return new FailureDescriptor(failed.failure().stage().name(), failed.failure().code().name(),
                    failed.failure().location().path(), null);
        return null;
    }

    private static boolean providerFailure(JobIntelligenceResult.Code code) {
        return code == JobIntelligenceResult.Code.PROVIDER_CONTRACT_VIOLATION
                || code.name().startsWith("PROVIDER_");
    }

    private String skillKey(String value) { return vocabulary.canonical(normalize(value)); }
    static String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }
    private static BigDecimal months(BigDecimal value, JobIntelligence.Unit unit) {
        if (value == null || unit == null) return null;
        return unit == JobIntelligence.Unit.YEARS ? value.multiply(TWELVE) : value;
    }
    private static boolean numericEquals(BigDecimal left, BigDecimal right) {
        return left == null ? right == null : right != null && left.compareTo(right) == 0;
    }

    private static boolean evidenceMatches(EvaluationCase value, List<String> evidenceIds,
            List<JobIntelligence.Evidence> evidence, List<EvidenceAnchor> anchors) {
        Map<String, JobIntelligence.Evidence> byId = new HashMap<>();
        evidence.forEach(item -> byId.put(item.id(), item));
        for (String id : evidenceIds) {
            JobIntelligence.Evidence predicted = byId.get(id);
            if (predicted == null) continue;
            String source = predicted.source() == JobIntelligence.Source.TITLE ? value.title() : value.description();
            List<Span> predictionSpans = allSpans(source, predicted.quote());
            for (EvidenceAnchor anchor : anchors) {
                if (anchor.source() != predicted.source()) continue;
                Span gold = occurrenceSpan(source, anchor.quote(), anchor.occurrence());
                if (gold != null && predictionSpans.stream().anyMatch(span -> span.overlaps(gold))) return true;
            }
        }
        return false;
    }

    private static List<Span> allSpans(String source, String quote) {
        List<Span> spans = new ArrayList<>();
        for (int from = 0, at; quote != null && !quote.isEmpty() && (at = source.indexOf(quote, from)) >= 0; from = at + 1)
            spans.add(new Span(at, at + quote.length()));
        return spans;
    }
    private static Span occurrenceSpan(String source, String quote, int occurrence) {
        int count = 0;
        for (int from = 0, at; (at = source.indexOf(quote, from)) >= 0; from = at + 1) {
            if (++count == occurrence) return new Span(at, at + quote.length());
        }
        return null;
    }
    private static Set<String> keys(List<GoldClaim> values, JobIntelligence.Importance importance) {
        Set<String> result = new HashSet<>(); values.stream().filter(v -> v.importance() == importance).map(GoldClaim::key).forEach(result::add); return result;
    }
    private static Set<String> keysPredicted(java.util.Collection<ComparableClaim> values, JobIntelligence.Importance importance) {
        Set<String> result = new HashSet<>(); values.stream().filter(v -> v.importance() == importance).map(ComparableClaim::key).forEach(result::add); return result;
    }
    private static Set<String> intersection(Set<String> left, Set<String> right) { Set<String> out = new HashSet<>(left); out.retainAll(right); return out; }
    private static Set<String> difference(Set<String> left, Set<String> right) { Set<String> out = new HashSet<>(left); out.removeAll(right); return out; }
    private static String displayGold(String key, List<GoldClaim> gold) { return gold.stream().filter(g -> g.key().equals(key)).map(GoldClaim::label).findFirst().orElse(key); }
    private static String displayPrediction(String key, Map<String, ComparableClaim> values, JobIntelligence.Importance importance) {
        ComparableClaim value = values.get(key + "\u0000" + importance); return value == null ? key : value.label();
    }

    private record PredictedClaim(String text, JobIntelligence.Importance importance, List<String> evidenceIds) {}
    private record PredictedQualification(String text, JobIntelligence.Importance importance, List<String> evidenceIds) {}
    private record GoldClaim(String key, String label, JobIntelligence.Importance importance, List<EvidenceAnchor> support) {}
    private record ComparableClaim(String key, String label, JobIntelligence.Importance importance, List<String> evidenceIds) {}
    private record Span(int start, int end) { boolean overlaps(Span other) { return start < other.end && end > other.start; } }
}
