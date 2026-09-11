package com.jobcopilot.intelligence.evaluation;

import com.jobcopilot.intelligence.JobIntelligence;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import static com.jobcopilot.intelligence.evaluation.EvaluationResult.*;

final class EvaluationReportRenderer {
    private final JsonMapper json = JsonMapper.builder()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    RenderedReport render(EvaluationReport report) {
        return new RenderedReport(text(report), json.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n");
    }

    private static String text(EvaluationReport report) {
        StringBuilder out = new StringBuilder();
        ReproducibilityMetadata metadata = report.reportMetadata();
        out.append("JC-007 Phase 5 Evaluation\n")
                .append("mode=").append(metadata.mode()).append('\n')
                .append("dataset=").append(metadata.datasetVersion()).append('\n')
                .append("scriptedResponses=").append(metadata.scriptedResponseVersion()).append('\n')
                .append("matchingVocabulary=").append(metadata.matchingVocabularyVersion()).append('\n')
                .append("cases=").append(report.cases().size()).append("\n\n")
                .append("FIXTURE LIMITATION: fixture-backed results validate harness mechanics; they do not establish ")
                .append("strategy or real-provider superiority.\n")
                .append("FIXTURE LIMITATION: ").append(report.fixtureDisclosure().reusedHybridCandidatePayloads())
                .append("/").append(report.fixtureDisclosure().llmFirstCases())
                .append(" LLM_FIRST cases reuse the HYBRID candidate payload, so their scores are not independent ")
                .append("strategy-quality evidence.\n")
                .append("Metric scope: skill P/R/F1 matches normalized name + importance; evidence provenance is ")
                .append("reported separately. UNSPECIFIED skill metrics remain in report.json.\n\n")
                .append(String.format("%-20s %-17s %-17s %-11s %-11s %-11s %-9s%n",
                        "Strategy", "Required P/R/F1", "Preferred P/R/F1", "Req Qual F1", "Pref Qual F1",
                        "Exp exact", "Usable"));
        for (Strategy strategy : Strategy.values()) {
            StrategySummary summary = report.summary().strategies().get(strategy);
            out.append(String.format("%-20s %-17s %-17s %-11s %-11s %-11s %-9s%n",
                    strategy,
                    rates(summary.skills().rates().get(JobIntelligence.Importance.REQUIRED)),
                    rates(summary.skills().rates().get(JobIntelligence.Importance.PREFERRED)),
                    summary.qualifications() == null ? "—" : metric(summary.qualifications().rates()
                            .get(JobIntelligence.Importance.REQUIRED).f1()),
                    summary.qualifications() == null ? "—" : metric(summary.qualifications().rates()
                            .get(JobIntelligence.Importance.PREFERRED).f1()),
                    summary.experience().applicable() ? summary.experience().exact() + "/" + summary.experience().goldClauses() : "—",
                    summary.reliability().succeeded() + "/" + report.cases().size()));
        }
        out.append("\nEvidence-anchor mismatches (skill/qualification/experience)\n");
        for (Strategy strategy : Strategy.values()) {
            StrategySummary summary = report.summary().strategies().get(strategy);
            out.append(strategy).append(": ")
                    .append(summary.skills().evidenceAnchorMismatches()).append('/')
                    .append(summary.qualifications() == null ? "—" : summary.qualifications().evidenceAnchorMismatches())
                    .append('/').append(summary.experience().applicable()
                            ? summary.experience().evidenceAnchorMismatches() : "—")
                    .append('\n');
        }
        out.append("\nFailure breakdown\n")
                .append(String.format("%-20s %8s %8s %8s %8s %10s %8s%n",
                        "Strategy", "Baseline", "Provider", "Execute", "Decode", "Validate", "Defect"));
        for (Strategy strategy : Strategy.values()) {
            ReliabilitySummary value = report.summary().strategies().get(strategy).reliability();
            out.append(String.format("%-20s %8d %8d %8d %8d %10d %8d%n", strategy,
                    value.baselineUnavailable(), value.providerFailures(), value.executionFailures(),
                    value.decodeFailures(), value.validationFailures(), value.executionDefects()));
        }
        out.append("\nReproducibility by strategy\n");
        for (Strategy strategy : Strategy.values()) {
            ReproducibilityMetadata value = firstMetadata(report.cases(), strategy);
            out.append(strategy).append(": provider=").append(safe(value.providerId()))
                    .append(" requestedModel=").append(safe(value.requestedModel()))
                    .append(" returnedModel=").append(safe(value.returnedModel()))
                    .append(" prompt=").append(safe(value.promptIdentity()))
                    .append(" schema=").append(safe(value.schemaIdentity()))
                    .append(" validation=").append(safe(value.validationPolicyVersion())).append('\n');
        }
        out.append("\nCategory breakdown\n");
        report.summary().tags().keySet().stream().sorted().forEach(tag -> {
            Map<Strategy, TagStrategySummary> strategies = report.summary().tags().get(tag);
            out.append(tag).append(':');
            for (Strategy strategy : Strategy.values()) {
                TagStrategySummary value = strategies.get(strategy);
                out.append(' ').append(strategy).append("[cases=").append(value.cases())
                        .append(",usable=").append(value.succeeded())
                        .append(",req=").append(counts(value.requiredSkills()))
                        .append(",minExact=").append(value.minimumExact()).append(']');
            }
            out.append('\n');
        });
        out.append("\nPer-case disagreements\n");
        for (EvaluatedCase value : report.cases()) {
            if (!disagrees(value)) continue;
            out.append(value.caseId()).append(" tags=").append(String.join(",", value.tags())).append('\n');
            for (StrategyEvaluation strategy : value.strategies()) {
                Counts required = strategy.semantic().skills().byImportance().get(JobIntelligence.Importance.REQUIRED);
                Counts preferred = strategy.semantic().skills().byImportance().get(JobIntelligence.Importance.PREFERRED);
                out.append("  ").append(strategy.strategy()).append(" status=").append(strategy.terminalStatus())
                        .append(" required=").append(counts(required)).append(" preferred=").append(counts(preferred))
                        .append(" minExact=").append(strategy.semantic().minimumExperience().exact());
                if (strategy.failure() != null) out.append(" failure=").append(safe(strategy.failure().code()));
                out.append('\n');
            }
        }
        return out.toString();
    }

    private static boolean disagrees(EvaluatedCase value) {
        List<StrategyEvaluation> all = value.strategies();
        for (int i = 1; i < all.size(); i++) {
            StrategyEvaluation left = all.get(0), right = all.get(i);
            if (left.terminalStatus() != right.terminalStatus()
                    || !left.semantic().skills().byImportance().equals(right.semantic().skills().byImportance())
                    || left.semantic().minimumExperience().exact() != right.semantic().minimumExperience().exact()) return true;
        }
        return !value.hybrid().semantic().equals(value.llmFirst().semantic());
    }

    private static ReproducibilityMetadata firstMetadata(List<EvaluatedCase> cases, Strategy strategy) {
        StrategyEvaluation value = switch (strategy) {
            case JC005 -> cases.getFirst().baseline();
            case HYBRID_ENRICHMENT -> cases.getFirst().hybrid();
            case LLM_FIRST -> cases.getFirst().llmFirst();
        };
        return value.metadata();
    }
    private static String rates(Rates rates) { return metric(rates.precision()) + "/" + metric(rates.recall()) + "/" + metric(rates.f1()); }
    private static String metric(BigDecimal value) { return value == null ? "—" : value.stripTrailingZeros().toPlainString(); }
    private static String counts(Counts value) { return value.truePositive() + "/" + value.falsePositive() + "/" + value.falseNegative(); }
    private static String safe(String value) { return value == null || value.isBlank() ? "—" : value; }
}
