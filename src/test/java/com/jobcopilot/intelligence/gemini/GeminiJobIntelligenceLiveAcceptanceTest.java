package com.jobcopilot.intelligence.gemini;

import com.jobcopilot.intelligence.JobIntelligenceModel;
import com.jobcopilot.intelligence.JobIntelligencePrompt;
import com.jobcopilot.intelligence.JobIntelligenceResult;
import com.jobcopilot.intelligence.JobIntelligenceRunner;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named = "jobcopilot.gemini.live", matches = "true")
class GeminiJobIntelligenceLiveAcceptanceTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String MODEL = System.getProperty("jobcopilot.gemini.live.model", "gemini-3.5-flash-lite");
    private static final Duration TIMEOUT = Duration.ofSeconds(45);

    @Test void purposefulRealProviderSetTraversesTheNormalPipeline() {
        String apiKey = System.getenv("GEMINI_API_KEY");
        assertThat(apiKey).as("GEMINI_API_KEY must be present for the opt-in live test").isNotBlank();
        var delegate = new GeminiJobIntelligenceModel(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build(),
                URI.create("https://generativelanguage.googleapis.com"), apiKey);
        List<Attempt> attempts = List.of(
                new Attempt("A-positive", new JobIntelligencePrompt.LlmFirstInput(
                        "Backend Engineer",
                        "Java and Spring Boot are required. Docker is preferred. "
                                + "At least 3 years of professional experience is required.")),
                new Attempt("B-stale-canonical", new JobIntelligencePrompt.HybridInput(
                        "Platform Engineer",
                        "Kotlin is required. PostgreSQL is preferred. "
                                + "At least 2 years of experience is required.",
                        new JobIntelligencePrompt.CanonicalRequirements(List.of("Python"), List.of("AWS"),
                                new java.math.BigDecimal("5")))),
                new Attempt("C-ambiguity", new JobIntelligencePrompt.LlmFirstInput(
                        "Software Engineer",
                        "3 years of experience or a bachelor's degree is required.")));

        var results = new ArrayList<JobIntelligenceResult>();
        for (Attempt attempt : attempts) {
            AtomicInteger invocations = new AtomicInteger();
            var observed = new java.util.concurrent.atomic.AtomicReference<JobIntelligenceModel.ModelInput>();
            JobIntelligenceModel counted = new JobIntelligenceModel() {
                public String providerId() { return delegate.providerId(); }
                public ModelAttemptResult analyze(ModelInput input) {
                    invocations.incrementAndGet(); observed.set(input); return delegate.analyze(input);
                }
            };
            JobIntelligenceResult result = new JobIntelligenceRunner(new JobIntelligencePrompt(), counted).run(
                    new JobIntelligenceRunner.Request(attempt.input(), new JobIntelligencePrompt.ExecutionSettings(
                            MODEL, new JobIntelligenceModel.GenerationSettings(0.0, 4096), TIMEOUT)));
            results.add(result);
            assertThat(invocations).as(attempt.name()).hasValue(1);
            assertIsolation(attempt, observed.get());
            assertThat(result.metadata().invocationStarted()).as(attempt.name()).isTrue();
            printSanitized(attempt, result, invocations.get());
        }

        assertThat(results).anyMatch(JobIntelligenceResult.Accepted.class::isInstance);
        assertThat(results.get(0).metadata().strategy()).isEqualTo(JobIntelligenceResult.Strategy.LLM_FIRST);
        assertThat(results.get(1).metadata().strategy()).isEqualTo(JobIntelligenceResult.Strategy.HYBRID_ENRICHMENT);
    }

    private static void assertIsolation(Attempt attempt, JobIntelligenceModel.ModelInput input) {
        assertThat(input).isNotNull();
        JsonNode data = JSON.readTree(input.strategyData());
        assertThat(data.propertyNames()).containsExactlyInAnyOrder(attempt.input() instanceof JobIntelligencePrompt.HybridInput
                ? new String[] {"title", "description", "canonicalRequirements"}
                : new String[] {"title", "description"});
        assertThat(input.strategyData()).doesNotContain("A-positive", "B-stale-canonical", "C-ambiguity",
                "gold", "tags", "reviewStatus", "fixture", "baseline", "JC005");
        if (attempt.input() instanceof JobIntelligencePrompt.HybridInput)
            assertThat(data.get("canonicalRequirements").isObject()).isTrue();
        else assertThat(data.has("canonicalRequirements")).isFalse();
    }

    private static void printSanitized(Attempt attempt, JobIntelligenceResult result, int invocations) {
        String terminal = result instanceof JobIntelligenceResult.Accepted ? "ACCEPTED" : "FAILED";
        String provider = result.metadata().providerOutcome() == null ? "NONE" : result.metadata().providerOutcome().name();
        String stage = result instanceof JobIntelligenceResult.Failed failed ? failed.failure().stage().name() : "COMPLETE";
        String code = result instanceof JobIntelligenceResult.Failed failed ? failed.failure().code().name() : "NONE";
        String minimum = result instanceof JobIntelligenceResult.Accepted accepted
                ? accepted.intelligence().minimumExperience().status() + "/"
                        + accepted.intelligence().minimumExperience().months()
                : "NOT_REACHED";
        System.out.println("GEMINI_ACCEPTANCE case=" + attempt.name() + " strategy=" + result.metadata().strategy()
                + " model=" + safe(result.metadata().returnedModel(), MODEL) + " invocations=" + invocations
                + " provider=" + provider + " stage=" + stage + " terminal=" + terminal + " code=" + code
                + " javaMinimum=" + minimum + " retryRepairFallback=0");
    }

    private static String safe(String value, String fallback) {
        if (value == null || value.isBlank() || !value.matches("[A-Za-z0-9._:/-]{1,200}")) return fallback;
        return value;
    }

    private record Attempt(String name, JobIntelligencePrompt.StrategyInput input) {}
}
