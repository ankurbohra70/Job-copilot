package com.jobcopilot.intelligence;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static com.jobcopilot.intelligence.JobIntelligenceTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class JobIntelligenceConcurrencyQaTest {
    private static JobIntelligenceModel.ModelInput input() {
        return new JobIntelligencePrompt().assemble(new JobIntelligencePrompt.LlmFirstInput("R", "D"), settings());
    }
    @Test void saturatedPoolRejectsWithoutStartingAnotherInvocation() throws Exception {
        var pool = JobIntelligenceAttempt.boundedExecutor();
        var started = new CountDownLatch(4);
        var release = new CountDownLatch(1);
        try {
            for (int i = 0; i < 4; i++) pool.execute(() -> { started.countDown(); await(release); });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            for (int i = 0; i < 16; i++) pool.execute(() -> {});
            var model = new ScriptedModel(javaRequired());
            var result = new JobIntelligenceRunner(new JobIntelligencePrompt(), model, pool, System::nanoTime, JobIntelligenceAttempt::await)
                    .run(request(new JobIntelligencePrompt.LlmFirstInput("R", "D")));
            var failed = assertInstanceOf(JobIntelligenceResult.Failed.class, result);
            assertEquals(JobIntelligenceResult.Code.EXECUTION_UNAVAILABLE, failed.failure().code());
            assertFalse(failed.metadata().invocationStarted());
            assertEquals(0, model.invocations());
            assertEquals(4, pool.getMaximumPoolSize());
            assertEquals(16, pool.getQueue().size());
        } finally { release.countDown(); pool.shutdown(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS)); }
    }
    @Test void queuedTimeoutRemovesTaskAndDoesNotCallAnalyze() throws Exception {
        var pool = JobIntelligenceAttempt.boundedExecutor();
        var started = new CountDownLatch(4);
        var release = new CountDownLatch(1);
        var now = new AtomicLong(100);
        try {
            for (int i = 0; i < 4; i++) pool.execute(() -> { started.countDown(); await(release); });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            var model = new ScriptedModel(javaRequired());
            var attempt = new JobIntelligenceAttempt(pool, now::get, (future, remaining) -> {
                assertEquals(100, remaining);
                assertEquals(1, pool.getQueue().size());
                now.addAndGet(100);
                return JobIntelligenceAttempt.Waiter.Outcome.TIMEOUT;
            });
            var result = attempt.run(model, input(), Duration.ofNanos(100));
            assertEquals(JobIntelligenceAttempt.Terminal.TIMEOUT, result.terminal());
            assertFalse(result.invocationStarted());
            assertEquals(0, pool.getQueue().size());
            assertEquals(0, model.invocations());
        } finally { release.countDown(); pool.shutdown(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS)); }
    }
    @Test void lateProviderCompletionCannotReplaceTimeoutAndCancellationIsRequested() throws Exception {
        var pool = JobIntelligenceAttempt.boundedExecutor();
        var entered = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var exited = new CountDownLatch(1);
        var model = new ScriptedModel(in -> {
            entered.countDown();
            for (;;) {
                try { release.await(); break; }
                catch (InterruptedException e) { interrupted.countDown(); }
            }
            exited.countDown();
            return ScriptedModel.completed(javaRequired());
        });
        try {
            var attempt = new JobIntelligenceAttempt(pool, System::nanoTime, (future, remaining) -> {
                await(entered);
                return JobIntelligenceAttempt.Waiter.Outcome.TIMEOUT;
            });
            var result = attempt.run(model, input(), Duration.ofSeconds(30));
            assertTrue(interrupted.await(5, TimeUnit.SECONDS));
            assertTrue(result.invocationStarted());
            assertEquals(JobIntelligenceAttempt.Terminal.TIMEOUT, result.terminal());
            release.countDown();
            assertTrue(exited.await(5, TimeUnit.SECONDS));
            assertNull(result.output());
            assertEquals(1, model.invocations());
        } finally { release.countDown(); pool.shutdown(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS)); }
    }
    @Test void completedAfterDeadlineStillTimesOut() {
        var now = new AtomicLong(100);
        var model = new ScriptedModel(in -> { now.addAndGet(101); return ScriptedModel.completed(javaRequired()); });
        var result = new JobIntelligenceAttempt(directExecutor(), now::get, (future, remaining) -> JobIntelligenceAttempt.Waiter.Outcome.COMPLETED)
                .run(model, input(), Duration.ofNanos(100));
        assertEquals(JobIntelligenceAttempt.Terminal.TIMEOUT, result.terminal());
        assertEquals(1, model.invocations());
    }
    @Test void interruptedCallerDoesNotDispatch() {
        var model = new ScriptedModel(javaRequired());
        try {
            Thread.currentThread().interrupt();
            var result = new JobIntelligenceAttempt(directExecutor(), System::nanoTime, JobIntelligenceAttempt::await)
                    .run(model, input(), Duration.ofSeconds(1));
            assertEquals(JobIntelligenceAttempt.Terminal.INTERRUPTED, result.terminal());
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(0, model.invocations());
        } finally { Thread.interrupted(); }
    }
    @Test void providerIdExceptionIsBoundedBeforeAnalyze() {
        var model = new JobIntelligenceModel() {
            public String providerId() { throw new IllegalStateException("secret"); }
            public ModelAttemptResult analyze(ModelInput input) { fail("must not execute"); return null; }
        };
        var result = assertInstanceOf(JobIntelligenceResult.Failed.class,
                runner(model).run(request(new JobIntelligencePrompt.LlmFirstInput("R", "D"))));
        assertEquals(JobIntelligenceResult.Code.INVOCATION_EXCEPTION, result.failure().code());
        assertFalse(result.metadata().invocationStarted());
        assertFalse(result.toString().contains("secret"));
    }
    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }
}
