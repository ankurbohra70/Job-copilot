package com.jobcopilot.intelligence.evaluation;

import com.jobcopilot.intelligence.JobIntelligence;
import org.junit.jupiter.api.Test;
import static com.jobcopilot.intelligence.evaluation.EvaluationResult.*;
import static org.assertj.core.api.Assertions.assertThat;

class MetricsAggregatorTest {
    @Test
    void usesExplicitNullForZeroDenominatorRates() {
        Rates rates = MetricsAggregator.rates(new Counts(0, 0, 0));

        assertThat(rates.precision()).isNull();
        assertThat(rates.recall()).isNull();
        assertThat(rates.f1()).isNull();
    }

    @Test
    void aggregatesReliabilitySeparatelyFromSemanticQuality() {
        EvaluationSummary summary = EvaluationRunnerTest.evaluateFixtureBacked().summary();
        StrategySummary hybrid = summary.strategies().get(Strategy.HYBRID_ENRICHMENT);
        StrategySummary llm = summary.strategies().get(Strategy.LLM_FIRST);

        assertThat(hybrid.reliability().validationFailures()).isEqualTo(1);
        assertThat(llm.reliability().validationFailures()).isZero();
        assertThat(llm.skills().counts().get(JobIntelligence.Importance.REQUIRED).truePositive())
                .isGreaterThan(0);
        assertThat(summary.tags().get("sparse").get(Strategy.LLM_FIRST).cases()).isEqualTo(1);
    }
}
