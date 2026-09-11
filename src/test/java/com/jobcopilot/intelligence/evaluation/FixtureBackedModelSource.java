package com.jobcopilot.intelligence.evaluation;

import com.jobcopilot.intelligence.JobIntelligenceModel;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import tools.jackson.databind.json.JsonMapper;
import static com.jobcopilot.intelligence.evaluation.EvaluationResult.Strategy;

final class FixtureBackedModelSource implements EvaluationCoordinator.ModelSource {
    private final Map<String, ScriptedCaseResponse> responses;
    private final Map<Key, AtomicInteger> invocations = new HashMap<>();
    private final JsonMapper json = JsonMapper.builder().build();

    FixtureBackedModelSource(ScriptedResponseSet responseSet) {
        responses = new HashMap<>();
        for (ScriptedCaseResponse response : responseSet.cases()) responses.put(response.caseId(), response);
    }

    @Override
    public JobIntelligenceModel modelFor(String caseId, Strategy strategy) {
        if (strategy == Strategy.JC005) throw new IllegalArgumentException("JC-005 does not use a model");
        ScriptedCaseResponse response = responses.get(caseId);
        if (response == null) throw new IllegalArgumentException("Unknown fixture case");
        ConfiguredAttempt selected = strategy == Strategy.HYBRID_ENRICHMENT ? response.hybrid() : response.llmFirst();
        final ConfiguredAttempt configured = "HYBRID".equals(selected.candidateRef()) ? response.hybrid() : selected;
        Key key = new Key(caseId, strategy);
        AtomicInteger count = new AtomicInteger();
        if (invocations.putIfAbsent(key, count) != null) throw new IllegalStateException("Fixture model requested twice");
        return new JobIntelligenceModel() {
            @Override public String providerId() { return "fixture"; }
            @Override public ModelAttemptResult analyze(ModelInput input) {
                count.incrementAndGet();
                String candidate = configured.candidateJson();
                if (candidate == null && configured.candidate() != null) {
                    candidate = json.writeValueAsString(configured.candidate());
                }
                return new ModelAttemptResult(candidate, configured.outcome(), configured.failureCode(),
                        "jc007-fixture-v1", null, new Usage(0L, 0L, 0L), Duration.ZERO);
            }
        };
    }

    int invocationCount(String caseId, Strategy strategy) {
        AtomicInteger count = invocations.get(new Key(caseId, strategy));
        return count == null ? 0 : count.get();
    }

    private record Key(String caseId, Strategy strategy) {}
}
