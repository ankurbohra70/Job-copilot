package com.jobcopilot.intelligence;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Bounded synchronous provider invocation. One submit, no retries, monotonic deadline. */
final class JobIntelligenceAttempt {
    @FunctionalInterface
    interface Clock { long nanoTime(); }

    @FunctionalInterface
    interface Waiter {
        Outcome await(Future<?> future, long remainingNanos);
        enum Outcome { COMPLETED, TIMEOUT, INTERRUPTED }
    }

    record Execution(JobIntelligenceModel.ModelAttemptResult output, Terminal terminal, Duration elapsed,
            boolean invocationStarted, String providerId) {}
    enum Terminal { OUTPUT, TIMEOUT, INTERRUPTED, INVOCATION_EXCEPTION, UNAVAILABLE }

    private final ExecutorService executor;
    private final Clock clock;
    private final Waiter waiter;

    JobIntelligenceAttempt(ExecutorService executor, Clock clock, Waiter waiter) {
        this.executor = Objects.requireNonNull(executor);
        this.clock = Objects.requireNonNull(clock);
        this.waiter = Objects.requireNonNull(waiter);
    }

    static JobIntelligenceAttempt realtime() {
        return new JobIntelligenceAttempt(Shared.EXECUTOR, System::nanoTime, JobIntelligenceAttempt::await);
    }

    // Process-owned and shared across runners: stuck adapters cannot multiply pools per request.
    private static final class Shared { static final ThreadPoolExecutor EXECUTOR = boundedExecutor(); }

    static ThreadPoolExecutor boundedExecutor() {
        var pool = new ThreadPoolExecutor(4, 4, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(16), threadFactory(), new ThreadPoolExecutor.AbortPolicy());
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    Execution run(JobIntelligenceModel model, JobIntelligenceModel.ModelInput input, Duration timeout) {
        long start = clock.nanoTime();
        long budget = timeout.toNanos();
        AtomicInteger state = new AtomicInteger(); // 0 pending, 1 analyze entered, 2 cancelled before analyze
        AtomicReference<String> providerId = new AtomicReference<>();
        AtomicReference<Long> completedAt = new AtomicReference<>();
        Future<JobIntelligenceModel.ModelAttemptResult> future;
        if (Thread.currentThread().isInterrupted())
            return new Execution(null, Terminal.INTERRUPTED, elapsed(start), false, null);
        try {
            future = executor.submit((Callable<JobIntelligenceModel.ModelAttemptResult>) () -> {
                try {
                    if (clock.nanoTime() - start >= budget || state.get() == 2) return null;
                    // Even a broken providerId implementation is inside the bounded provider boundary.
                    providerId.set(model.providerId());
                    long remainingBudget = budget - (clock.nanoTime() - start);
                    if (remainingBudget <= 0 || !state.compareAndSet(0, 1)) return null;
                    return model.analyze(JobIntelligencePrompt.withRemainingTimeout(input, Duration.ofNanos(remainingBudget)));
                } finally { completedAt.set(clock.nanoTime()); }
            });
        } catch (RejectedExecutionException rejected) {
            return new Execution(null, Terminal.UNAVAILABLE, elapsed(start), false, null);
        }
        long remaining = budget - (clock.nanoTime() - start);
        Waiter.Outcome wait = waiter.await(future, remaining);
        if (wait == Waiter.Outcome.TIMEOUT) {
            cancel(future, state);
            return new Execution(null, Terminal.TIMEOUT, elapsed(start), state.get() == 1, providerId.get());
        }
        if (wait == Waiter.Outcome.INTERRUPTED) {
            Thread.currentThread().interrupt();
            cancel(future, state);
            return new Execution(null, Terminal.INTERRUPTED, elapsed(start), state.get() == 1, providerId.get());
        }
        try {
            JobIntelligenceModel.ModelAttemptResult output = future.get();
            if (completedAt.get() == null || completedAt.get() - start >= budget || state.get() != 1)
                return new Execution(null, Terminal.TIMEOUT, elapsed(start), state.get() == 1, providerId.get());
            return new Execution(output, Terminal.OUTPUT, elapsed(start), true, providerId.get());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            cancel(future, state);
            return new Execution(null, Terminal.INTERRUPTED, elapsed(start), state.get() == 1, providerId.get());
        } catch (CancellationException cancelled) {
            return new Execution(null, Terminal.TIMEOUT, elapsed(start), state.get() == 1, providerId.get());
        } catch (ExecutionException execution) {
            Throwable cause = execution.getCause() == null ? execution : execution.getCause();
            if (cause instanceof Error error) throw error;
            return new Execution(null, Terminal.INVOCATION_EXCEPTION, elapsed(start), state.get() == 1, providerId.get());
        }
    }

    private void cancel(Future<?> future, AtomicInteger state) {
        state.compareAndSet(0, 2);
        future.cancel(true);
        if (executor instanceof ThreadPoolExecutor pool && future instanceof Runnable task) pool.remove(task);
    }

    static Waiter.Outcome await(Future<?> future, long remainingNanos) {
        try {
            if (remainingNanos <= 0) return Waiter.Outcome.TIMEOUT;
            future.get(remainingNanos, TimeUnit.NANOSECONDS);
            return Waiter.Outcome.COMPLETED;
        } catch (TimeoutException timeout) {
            return Waiter.Outcome.TIMEOUT;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Waiter.Outcome.INTERRUPTED;
        } catch (CancellationException cancelled) {
            return Waiter.Outcome.TIMEOUT;
        } catch (ExecutionException execution) {
            return Waiter.Outcome.COMPLETED;
        }
    }

    private Duration elapsed(long start) {
        long nanos = clock.nanoTime() - start;
        return Duration.ofNanos(Math.max(0, nanos));
    }

    private static java.util.concurrent.ThreadFactory threadFactory() {
        return task -> {
            Thread thread = new Thread(task, "job-intelligence-attempt");
            thread.setDaemon(true);
            return thread;
        };
    }
}
