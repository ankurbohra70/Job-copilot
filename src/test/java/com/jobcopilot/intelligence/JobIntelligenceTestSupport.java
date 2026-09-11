package com.jobcopilot.intelligence;

import java.time.Duration;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import com.jobcopilot.intelligence.JobIntelligencePrompt.ExecutionSettings;
import com.jobcopilot.intelligence.JobIntelligencePrompt.StrategyInput;

final class JobIntelligenceTestSupport {
    private JobIntelligenceTestSupport() {}

    static ExecutionSettings settings() {
        return JobIntelligencePromptTest.settings();
    }

    static JobIntelligenceRunner.Request request(StrategyInput input) {
        return new JobIntelligenceRunner.Request(input, settings());
    }

    static AbstractExecutorService directExecutor() {
        return new AbstractExecutorService() {
            private volatile boolean shutdown;
            @Override public void shutdown() { shutdown = true; }
            @Override public java.util.List<Runnable> shutdownNow() { shutdown = true; return java.util.List.of(); }
            @Override public boolean isShutdown() { return shutdown; }
            @Override public boolean isTerminated() { return shutdown; }
            @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
            @Override public void execute(Runnable command) { command.run(); }
        };
    }

    static JobIntelligenceRunner runner(JobIntelligenceModel model) {
        return new JobIntelligenceRunner(new JobIntelligencePrompt(), model, directExecutor(), System::nanoTime, JobIntelligenceAttempt::await);
    }

    static JobIntelligenceRunner runner(JobIntelligenceModel model, JobIntelligenceAttempt.Waiter waiter) {
        return new JobIntelligenceRunner(new JobIntelligencePrompt(), model, directExecutor(), System::nanoTime, waiter);
    }

    static String fixture(String name) throws Exception {
        try (var stream = JobIntelligenceTestSupport.class.getResourceAsStream("/job-intelligence/fixtures/" + name + ".json")) {
            return new String(stream.readAllBytes());
        }
    }

    static String emptyFacts(String extraEvidence, String extraFacts, String interpretations, String uncertainties) {
        return """
                {"facts":{"skills":[%s],"experienceClauses":[],"qualifications":[]},\
                "interpretations":{"roleFamily":null,"seniority":null,"responsibilities":[],"technicalConcepts":[]},\
                "evidence":[%s],"uncertainties":[%s]}
                """.formatted(extraFacts, extraEvidence, uncertainties);
    }

    static String javaRequired() {
        return """
                {"facts":{"skills":[{"name":"Java","importance":"REQUIRED","evidenceIds":["e1"]}],\
                "experienceClauses":[],"qualifications":[]},"interpretations":{"roleFamily":null,"seniority":null,\
                "responsibilities":[],"technicalConcepts":[]},"evidence":[{"id":"e1","source":"DESCRIPTION",\
                "quote":"Must have Java."}],"uncertainties":[]}
                """;
    }

    static String withExperience(String text, String minimum, String unit, String importance, String scope, boolean conditional, String quote) {
        String min = minimum == null ? "null" : minimum;
        String u = unit == null ? "null" : "\"" + unit + "\"";
        return """
                {"facts":{"skills":[],"experienceClauses":[{"text":%s,"minimum":%s,"unit":%s,"importance":"%s",\
                "scope":"%s","conditional":%s,"evidenceIds":["e1"]}],"qualifications":[]},\
                "interpretations":{"roleFamily":null,"seniority":null,"responsibilities":[],"technicalConcepts":[]},\
                "evidence":[{"id":"e1","source":"DESCRIPTION","quote":%s}],"uncertainties":[]}
                """.formatted(jsonString(text), min, u, importance, scope, conditional, jsonString(quote));
    }

    static String jsonString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    static final class ScriptedModel implements JobIntelligenceModel {
        private final AtomicInteger invocations = new AtomicInteger();
        private final Function<ModelInput, ModelAttemptResult> script;
        ScriptedModel(Function<ModelInput, ModelAttemptResult> script) { this.script = script; }
        ScriptedModel(ModelAttemptResult result) { this(input -> result); }
        ScriptedModel(String json) { this(completed(json)); }
        @Override public String providerId() { return "scripted"; }
        @Override public ModelAttemptResult analyze(ModelInput input) {
            invocations.incrementAndGet();
            return script.apply(input);
        }
        int invocations() { return invocations.get(); }
        static ModelAttemptResult completed(String json) {
            return new ModelAttemptResult(json, Outcome.COMPLETED, null, "returned-model", "req-1",
                    new Usage(1L, 2L, 0L), Duration.ofMillis(3));
        }
        static ModelAttemptResult outcome(Outcome outcome) {
            return new ModelAttemptResult(null, outcome, "provider-code", null, null, null, null);
        }
    }
}
