package com.jobcopilot.intelligence.evaluation;

import com.jobcopilot.intelligence.JobIntelligence;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import static com.jobcopilot.intelligence.evaluation.EvaluationResult.*;

final class MetricsAggregator {
    EvaluationSummary aggregate(List<EvaluatedCase> input) {
        List<EvaluatedCase> cases = input.stream().sorted(java.util.Comparator.comparing(EvaluatedCase::caseId)).toList();
        EnumMap<Strategy, StrategySummary> strategies = new EnumMap<>(Strategy.class);
        for (Strategy strategy : Strategy.values()) {
            List<StrategyEvaluation> values = cases.stream().map(value -> strategy(value, strategy)).toList();
            strategies.put(strategy, summarize(strategy, values));
        }
        Map<String, Map<Strategy, TagStrategySummary>> tags = new TreeMap<>();
        for (String tag : cases.stream().flatMap(value -> value.tags().stream()).distinct().sorted().toList()) {
            EnumMap<Strategy, TagStrategySummary> byStrategy = new EnumMap<>(Strategy.class);
            List<EvaluatedCase> tagged = cases.stream().filter(value -> value.tags().contains(tag)).toList();
            for (Strategy strategy : Strategy.values()) {
                List<StrategyEvaluation> values = tagged.stream().map(value -> strategy(value, strategy)).toList();
                Counts required = values.stream().map(value -> value.semantic().skills().byImportance()
                        .get(JobIntelligence.Importance.REQUIRED)).reduce(new Counts(0, 0, 0), Counts::plus);
                byStrategy.put(strategy, new TagStrategySummary(values.size(),
                        count(values, TerminalStatus.SUCCEEDED), required,
                        (int) values.stream().filter(value -> value.semantic().minimumExperience().exact()).count()));
            }
            tags.put(tag, byStrategy);
        }
        return new EvaluationSummary(strategies, tags);
    }

    private StrategySummary summarize(Strategy strategy, List<StrategyEvaluation> values) {
        ClaimSummary skills = claim(values.stream().map(value -> value.semantic().skills()).toList());
        ClaimSummary qualifications = strategy == Strategy.JC005 ? null
                : claim(values.stream().map(value -> value.semantic().qualifications()).toList());
        ExperienceSummary experience = strategy == Strategy.JC005
                ? new ExperienceSummary(false, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
                : experience(values);
        MinimumSummary minimum = minimum(values);
        ReliabilitySummary reliability = new ReliabilitySummary(
                count(values, TerminalStatus.SUCCEEDED),
                (int) values.stream().filter(StrategyEvaluation::acceptedAbstention).count(),
                (int) values.stream().filter(StrategyEvaluation::correctAbstention).count(),
                (int) values.stream().filter(StrategyEvaluation::abstentionWithMissedGold).count(),
                count(values, TerminalStatus.BASELINE_UNAVAILABLE), count(values, TerminalStatus.PREFLIGHT_FAILURE),
                count(values, TerminalStatus.PROVIDER_FAILURE), count(values, TerminalStatus.EXECUTION_FAILURE),
                count(values, TerminalStatus.DECODE_FAILURE), count(values, TerminalStatus.VALIDATION_FAILURE),
                count(values, TerminalStatus.EXECUTION_DEFECT));
        return new StrategySummary(strategy, skills, qualifications, experience, minimum, reliability);
    }

    private static ClaimSummary claim(List<ClaimBreakdown> values) {
        EnumMap<JobIntelligence.Importance, Counts> counts = new EnumMap<>(JobIntelligence.Importance.class);
        EnumMap<JobIntelligence.Importance, Rates> rates = new EnumMap<>(JobIntelligence.Importance.class);
        for (JobIntelligence.Importance importance : JobIntelligence.Importance.values()) {
            Counts total = values.stream().map(value -> value.byImportance().get(importance))
                    .reduce(new Counts(0, 0, 0), Counts::plus);
            counts.put(importance, total);
            rates.put(importance, rates(total));
        }
        return new ClaimSummary(counts, rates,
                values.stream().mapToInt(ClaimBreakdown::requiredToPreferred).sum(),
                values.stream().mapToInt(ClaimBreakdown::preferredToRequired).sum(),
                values.stream().mapToInt(ClaimBreakdown::duplicatePredictions).sum(),
                values.stream().mapToInt(ClaimBreakdown::crossClassConflicts).sum(),
                values.stream().mapToInt(ClaimBreakdown::evidenceAnchorMismatches).sum());
    }

    private static ExperienceSummary experience(List<StrategyEvaluation> values) {
        List<ExperienceBreakdown> items = values.stream().map(value -> value.semantic().experience()).toList();
        return new ExperienceSummary(true,
                items.stream().mapToInt(ExperienceBreakdown::goldClauses).sum(),
                items.stream().mapToInt(ExperienceBreakdown::detected).sum(),
                items.stream().mapToInt(ExperienceBreakdown::missed).sum(),
                items.stream().mapToInt(ExperienceBreakdown::extra).sum(),
                items.stream().mapToInt(ExperienceBreakdown::exact).sum(),
                items.stream().mapToInt(ExperienceBreakdown::quantityMismatches).sum(),
                items.stream().mapToInt(ExperienceBreakdown::importanceMismatches).sum(),
                items.stream().mapToInt(ExperienceBreakdown::scopeMismatches).sum(),
                items.stream().mapToInt(ExperienceBreakdown::conditionalityMismatches).sum(),
                items.stream().mapToInt(ExperienceBreakdown::evidenceAnchorMismatches).sum());
    }

    private static MinimumSummary minimum(List<StrategyEvaluation> values) {
        int exact = (int) values.stream().filter(value -> value.semantic().minimumExperience().exact()).count();
        int successful = count(values, TerminalStatus.SUCCEEDED);
        return new MinimumSummary(true, exact, values.size(), ratio(exact, values.size()), successful,
                ratio(exact, successful),
                (int) values.stream().filter(value -> value.semantic().minimumExperience().statusMismatch()).count(),
                (int) values.stream().filter(value -> value.semantic().minimumExperience().numericMismatch()).count(),
                (int) values.stream().filter(value -> value.semantic().minimumExperience().missingUsableResult()).count());
    }

    static Rates rates(Counts counts) {
        BigDecimal precision = ratio(counts.truePositive(), counts.truePositive() + counts.falsePositive());
        BigDecimal recall = ratio(counts.truePositive(), counts.truePositive() + counts.falseNegative());
        BigDecimal f1 = ratio(2 * counts.truePositive(),
                2 * counts.truePositive() + counts.falsePositive() + counts.falseNegative());
        return new Rates(precision, recall, f1);
    }

    private static BigDecimal ratio(int numerator, int denominator) {
        return denominator == 0 ? null : BigDecimal.valueOf(numerator)
                .divide(BigDecimal.valueOf(denominator), 4, RoundingMode.HALF_UP);
    }

    private static int count(List<StrategyEvaluation> values, TerminalStatus status) {
        return (int) values.stream().filter(value -> value.terminalStatus() == status).count();
    }

    private static StrategyEvaluation strategy(EvaluatedCase value, Strategy strategy) {
        return switch (strategy) {
            case JC005 -> value.baseline();
            case HYBRID_ENRICHMENT -> value.hybrid();
            case LLM_FIRST -> value.llmFirst();
        };
    }
}
