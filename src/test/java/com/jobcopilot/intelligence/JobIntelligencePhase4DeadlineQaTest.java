package com.jobcopilot.intelligence;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class JobIntelligencePhase4DeadlineQaTest {
    @ParameterizedTest @ValueSource(longs = {900, 999})
    void queueDelayIsDeductedBeforeProviderInvocation(long queueMillis) {
        var now = new AtomicLong();
        var observed = new AtomicReference<Duration>();
        var executor = new AbstractExecutorService() {
            public void shutdown() { }
            public List<Runnable> shutdownNow() { return List.of(); }
            public boolean isShutdown() { return false; }
            public boolean isTerminated() { return false; }
            public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
            public void execute(Runnable command) {
                now.addAndGet(Duration.ofMillis(queueMillis).toNanos());
                command.run();
            }
        };
        var model = new JobIntelligenceTestSupport.ScriptedModel(input -> {
            observed.set(input.remainingTimeout());
            var original = new JobIntelligencePrompt().assemble(new JobIntelligencePrompt.LlmFirstInput("T", "D"),
                    new JobIntelligencePrompt.ExecutionSettings("test-model",
                            new JobIntelligenceModel.GenerationSettings(null, 600), Duration.ofSeconds(1)));
            assertEquals(original, JobIntelligencePrompt.withRemainingTimeout(input, original.remainingTimeout()));
            return JobIntelligenceTestSupport.ScriptedModel.completed("{}");
        });
        var input = new JobIntelligencePrompt().assemble(new JobIntelligencePrompt.LlmFirstInput("T", "D"),
                new JobIntelligencePrompt.ExecutionSettings("test-model",
                        new JobIntelligenceModel.GenerationSettings(null, 600), Duration.ofSeconds(1)));
        var result = new JobIntelligenceAttempt(executor, now::get, JobIntelligenceAttempt::await)
                .run(model, input, Duration.ofSeconds(1));
        assertEquals(JobIntelligenceAttempt.Terminal.OUTPUT, result.terminal());
        assertEquals(Duration.ofMillis(1000 - queueMillis), observed.get());
        assertEquals(1, model.invocations());
        assertEquals(Duration.ofSeconds(1), input.remainingTimeout());
    }
}
