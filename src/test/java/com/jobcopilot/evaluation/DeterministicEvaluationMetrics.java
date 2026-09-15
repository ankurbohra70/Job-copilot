package com.jobcopilot.evaluation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class DeterministicEvaluationMetrics {
    private DeterministicEvaluationMetrics() {
    }

    public record SetMetrics(int truePositive, int falsePositive, int falseNegative,
            Double precision, Double recall, Double f1) {
    }

    public record BinaryRates(int falsePositive, int trueNegative, int abstained, int total,
            Double falsePositiveRate, Double abstentionRate) {
    }

    public static SetMetrics sets(Collection<String> expected, Collection<String> actual) {
        Set<String> gold = new LinkedHashSet<>(expected);
        Set<String> predicted = new LinkedHashSet<>(actual);
        int tp = (int) predicted.stream().filter(gold::contains).count();
        int fp = predicted.size() - tp;
        int fn = gold.size() - tp;
        Double precision = ratio(tp, tp + fp);
        Double recall = ratio(tp, tp + fn);
        Double f1 = precision == null || recall == null || precision + recall == 0
                ? null : round(2 * precision * recall / (precision + recall));
        return new SetMetrics(tp, fp, fn, precision, recall, f1);
    }

    public static Double accuracy(long correct, long total) {
        return ratio(correct, total);
    }

    public static Double rate(long occurrences, long opportunities) {
        return ratio(occurrences, opportunities);
    }

    public static BinaryRates binaryRates(List<Boolean> expectedPositive, List<Boolean> predictedPositive,
            List<Boolean> abstained) {
        if (expectedPositive.size() != predictedPositive.size() || expectedPositive.size() != abstained.size())
            throw new IllegalArgumentException("binary labels must have equal sizes");
        int fp = 0;
        int tn = 0;
        int abstentions = 0;
        for (int index = 0; index < expectedPositive.size(); index++) {
            if (abstained.get(index)) abstentions++;
            if (!expectedPositive.get(index) && predictedPositive.get(index)) fp++;
            if (!expectedPositive.get(index) && !predictedPositive.get(index)) tn++;
        }
        return new BinaryRates(fp, tn, abstentions, expectedPositive.size(), ratio(fp, fp + tn),
                ratio(abstentions, expectedPositive.size()));
    }

    public static Double precisionAtK(List<String> rankedIds, Set<String> relevantIds, int k) {
        if (k <= 0 || rankedIds.size() < k) return null;
        long relevant = rankedIds.subList(0, k).stream().filter(relevantIds::contains).count();
        return ratio(relevant, k);
    }

    public static Double ndcgAtK(List<String> rankedIds, Map<String, Integer> relevance, int k) {
        if (k <= 0 || rankedIds.isEmpty()) return null;
        int limit = Math.min(k, rankedIds.size());
        double dcg = dcg(rankedIds.subList(0, limit), relevance);
        List<String> ideal = new ArrayList<>(relevance.keySet());
        ideal.sort((left, right) -> {
            int grade = Integer.compare(relevance.get(right), relevance.get(left));
            return grade != 0 ? grade : left.compareTo(right);
        });
        double idcg = dcg(ideal.subList(0, Math.min(limit, ideal.size())), relevance);
        return idcg == 0 ? null : round(dcg / idcg);
    }

    public static Map<String, Map<String, Integer>> confusion(List<String> expected, List<String> actual) {
        if (expected.size() != actual.size()) throw new IllegalArgumentException("labels must have equal sizes");
        Map<String, Map<String, Integer>> matrix = new LinkedHashMap<>();
        for (int index = 0; index < expected.size(); index++) {
            matrix.computeIfAbsent(expected.get(index), ignored -> new LinkedHashMap<>())
                    .merge(actual.get(index), 1, Integer::sum);
        }
        return matrix;
    }

    public static long falseSkipCount(List<String> actualDecisions, List<Boolean> applyWorthy) {
        requireSameSize(actualDecisions, applyWorthy);
        long count = 0;
        for (int index = 0; index < actualDecisions.size(); index++)
            if (applyWorthy.get(index) && "SKIP".equals(actualDecisions.get(index))) count++;
        return count;
    }

    public static long falseApplyDecisionCount(List<String> actualDecisions, List<Boolean> doNotApply) {
        requireSameSize(actualDecisions, doNotApply);
        long count = 0;
        for (int index = 0; index < actualDecisions.size(); index++)
            if (doNotApply.get(index) && Set.of("APPLY_VOLUME", "APPLY_PRECISION")
                    .contains(actualDecisions.get(index))) count++;
        return count;
    }

    public static long falseApplyReadinessCount(List<String> actualReadiness, List<Boolean> doNotApply) {
        requireSameSize(actualReadiness, doNotApply);
        long count = 0;
        for (int index = 0; index < actualReadiness.size(); index++)
            if (doNotApply.get(index) && "READY".equals(actualReadiness.get(index))) count++;
        return count;
    }

    private static double dcg(List<String> ids, Map<String, Integer> relevance) {
        double result = 0;
        for (int index = 0; index < ids.size(); index++) {
            int grade = relevance.getOrDefault(ids.get(index), 0);
            result += (Math.pow(2, grade) - 1) / (Math.log(index + 2) / Math.log(2));
        }
        return result;
    }

    private static Double ratio(long numerator, long denominator) {
        return denominator == 0 ? null : round((double) numerator / denominator);
    }

    private static double round(double value) {
        return Math.round(value * 1_000_000d) / 1_000_000d;
    }

    private static void requireSameSize(List<?> left, List<?> right) {
        if (left.size() != right.size()) throw new IllegalArgumentException("labels must have equal sizes");
    }
}
