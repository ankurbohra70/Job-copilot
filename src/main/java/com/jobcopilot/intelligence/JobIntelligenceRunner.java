package com.jobcopilot.intelligence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import com.jobcopilot.intelligence.JobIntelligencePrompt.ExecutionSettings;
import com.jobcopilot.intelligence.JobIntelligencePrompt.HybridInput;
import com.jobcopilot.intelligence.JobIntelligencePrompt.LlmFirstInput;
import com.jobcopilot.intelligence.JobIntelligencePrompt.StrategyInput;
import com.jobcopilot.intelligence.JobIntelligenceResult.AttemptMetadata;
import com.jobcopilot.intelligence.JobIntelligenceResult.Code;
import com.jobcopilot.intelligence.JobIntelligenceResult.Failed;
import com.jobcopilot.intelligence.JobIntelligenceResult.Failure;
import com.jobcopilot.intelligence.JobIntelligenceResult.Location;
import com.jobcopilot.intelligence.JobIntelligenceResult.Stage;
import com.jobcopilot.intelligence.JobIntelligenceResult.Strategy;

/**
 * In-memory acceptance pipeline: assemble → one model attempt → decode → validate → Java minimumExperience.
 * JC-005 baseline is not an argument and cannot influence execution.
 */
public final class JobIntelligenceRunner {
    public record Request(StrategyInput strategyInput, ExecutionSettings executionSettings) {}

    private final JobIntelligencePrompt prompt;
    private final JobIntelligenceModel model;
    private final JobIntelligenceAttempt attempt;
    private final JobIntelligenceDecoder decoder = new JobIntelligenceDecoder();
    private final JobIntelligenceValidator validator = new JobIntelligenceValidator();

    public JobIntelligenceRunner(JobIntelligencePrompt prompt, JobIntelligenceModel model) {
        this(prompt, model, JobIntelligenceAttempt.realtime());
    }

    JobIntelligenceRunner(JobIntelligencePrompt prompt, JobIntelligenceModel model, JobIntelligenceAttempt attempt) {
        this.prompt = Objects.requireNonNull(prompt);
        this.model = Objects.requireNonNull(model);
        this.attempt = Objects.requireNonNull(attempt);
    }

    JobIntelligenceRunner(JobIntelligencePrompt prompt, JobIntelligenceModel model, ExecutorService executor,
            JobIntelligenceAttempt.Clock clock, JobIntelligenceAttempt.Waiter waiter) {
        this(prompt, model, new JobIntelligenceAttempt(executor, clock, waiter));
    }

    public JobIntelligenceResult run(Request request) {
        long start = System.nanoTime();
        if (request == null || request.strategyInput() == null || request.executionSettings() == null) {
            return new Failed(new Failure(Stage.PREFLIGHT, Code.INVALID_REQUEST, Location.root()),
                    metadata(null, false, null, null, Duration.ZERO, start, null));
        }
        ExecutionSettings settings = request.executionSettings();
        StrategyInput strategyInput = request.strategyInput();
        Duration timeout = settings.remainingTimeout();
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            return new Failed(new Failure(Stage.PREFLIGHT, Code.INVALID_TIMEOUT, Location.root()),
                    metadata(strategyInput, false, settings, null, Duration.ZERO, start, null));
        }
        try { timeout.toNanos(); }
        catch (ArithmeticException overflow) {
            return new Failed(new Failure(Stage.PREFLIGHT, Code.INVALID_TIMEOUT, Location.root()),
                    metadata(strategyInput, false, settings, null, Duration.ZERO, start, null));
        }
        var generation = settings.generationSettings();
        if (settings.model() == null || settings.model().isBlank() || settings.model().length() > 256
                || generation.maxOutputTokens() <= 0
                || generation.temperature() != null && !Double.isFinite(generation.temperature())) {
            return new Failed(new Failure(Stage.PREFLIGHT, Code.INVALID_REQUEST, Location.root()),
                    metadata(strategyInput, false, settings, null, Duration.ZERO, start, null));
        }
        JobIntelligenceModel.ModelInput assembled = prompt.assemble(strategyInput, settings);
        RawSource source = raw(strategyInput);
        JobIntelligenceAttempt.Execution execution;
        execution = attempt.run(model, assembled, timeout);
        AttemptMetadata base = metadata(strategyInput, execution.invocationStarted(), settings, assembled, timeout, start, execution);
        if (execution.terminal() == JobIntelligenceAttempt.Terminal.UNAVAILABLE) {
            return new Failed(new Failure(Stage.EXECUTION, Code.EXECUTION_UNAVAILABLE, Location.root()), base);
        }
        if (execution.terminal() == JobIntelligenceAttempt.Terminal.TIMEOUT) {
            return new Failed(new Failure(Stage.EXECUTION, Code.TIMEOUT, Location.root()), withElapsed(base, execution.elapsed()));
        }
        if (execution.terminal() == JobIntelligenceAttempt.Terminal.INTERRUPTED) {
            return new Failed(new Failure(Stage.EXECUTION, Code.INTERRUPTED, Location.root()), withElapsed(base, execution.elapsed()));
        }
        if (execution.terminal() == JobIntelligenceAttempt.Terminal.INVOCATION_EXCEPTION) {
            return new Failed(new Failure(Stage.EXECUTION, Code.INVOCATION_EXCEPTION, Location.root()), withElapsed(base, execution.elapsed()));
        }
        JobIntelligenceModel.ModelAttemptResult provider = execution.output();
        AttemptMetadata withProvider = withProvider(base, provider, execution.elapsed());
        if (provider == null || provider.outcome() == null) {
            return new Failed(new Failure(Stage.EXECUTION, Code.PROVIDER_CONTRACT_VIOLATION, Location.root()), withProvider);
        }
        if (provider.outcome() != JobIntelligenceModel.Outcome.COMPLETED) {
            return new Failed(new Failure(Stage.EXECUTION, providerCode(provider.outcome()), Location.root()), withProvider);
        }
        JobIntelligenceDecoder.Result decoded;
        try {
            decoded = decoder.decode(provider.candidateJson());
        } catch (Error error) {
            throw error;
        } catch (RuntimeException exception) {
            return new Failed(new Failure(Stage.DECODE, Code.MALFORMED_JSON, Location.root()), withProvider);
        }
        if (decoded instanceof JobIntelligenceDecoder.Result.Failure failure) {
            return new Failed(new Failure(Stage.DECODE, failure.code(), failure.location()), withProvider);
        }
        JobIntelligenceModel.Output output = ((JobIntelligenceDecoder.Result.Success) decoded).output();
        JobIntelligenceValidator.Result validated;
        try {
            validated = validator.validate(output, source.title(), source.description());
        } catch (Error error) {
            throw error;
        } catch (RuntimeException exception) {
            return new Failed(new Failure(Stage.VALIDATION, Code.VALIDATOR_DEFECT, Location.root()), withProvider);
        }
        if (validated instanceof JobIntelligenceValidator.Result.Failure failure) {
            return new Failed(new Failure(Stage.VALIDATION, failure.code(), failure.location()), withProvider);
        }
        return new JobIntelligenceResult.Accepted(((JobIntelligenceValidator.Result.Success) validated).intelligence(), withProvider);
    }

    private static Code providerCode(JobIntelligenceModel.Outcome outcome) {
        return switch (outcome) {
            case REFUSED -> Code.PROVIDER_REFUSED;
            case INCOMPLETE -> Code.PROVIDER_INCOMPLETE;
            case RETRYABLE_FAILURE -> Code.PROVIDER_RETRYABLE_FAILURE;
            case PERMANENT_FAILURE -> Code.PROVIDER_PERMANENT_FAILURE;
            case COMPLETED -> Code.INVOCATION_EXCEPTION;
        };
    }

    private AttemptMetadata metadata(StrategyInput input, boolean invocationStarted, ExecutionSettings settings,
            JobIntelligenceModel.ModelInput assembled, Duration timeout, long start, JobIntelligenceAttempt.Execution execution) {
        Strategy strategy = input instanceof HybridInput ? Strategy.HYBRID_ENRICHMENT
                : input instanceof LlmFirstInput ? Strategy.LLM_FIRST : null;
        Duration elapsed = execution == null ? Duration.ofNanos(Math.max(0, System.nanoTime() - start)) : execution.elapsed();
        return new AttemptMetadata(
                strategy,
                invocationStarted,
                execution == null ? null : execution.providerId(),
                settings == null ? null : settings.model(),
                null,
                null,
                assembled == null ? null : assembled.promptVersion(),
                assembled == null ? null : assembled.schemaVersion(),
                settings == null ? null : settings.generationSettings(),
                timeout,
                elapsed,
                null,
                null,
                null,
                JobIntelligenceValidator.POLICY_VERSION,
                assembled == null ? null : digest(assembled.strategyData()));
    }

    private static AttemptMetadata withElapsed(AttemptMetadata metadata, Duration elapsed) {
        return new AttemptMetadata(metadata.strategy(), metadata.invocationStarted(), metadata.providerId(),
                metadata.requestedModel(), metadata.returnedModel(), metadata.providerRequestId(), metadata.promptIdentity(),
                metadata.schemaIdentity(), metadata.generationSettings(), metadata.effectiveTimeout(), elapsed,
                metadata.providerLatency(), metadata.tokenUsage(), metadata.providerOutcome(),
                metadata.validationPolicyVersion(), metadata.strategyDataDigest());
    }

    private static AttemptMetadata withProvider(AttemptMetadata metadata, JobIntelligenceModel.ModelAttemptResult provider, Duration elapsed) {
        if (provider == null) return withElapsed(metadata, elapsed);
        return new AttemptMetadata(metadata.strategy(), metadata.invocationStarted(), metadata.providerId(),
                metadata.requestedModel(), provider.returnedModel(), provider.providerRequestId(), metadata.promptIdentity(),
                metadata.schemaIdentity(), metadata.generationSettings(), metadata.effectiveTimeout(), elapsed,
                provider.latency(), provider.usage(), provider.outcome(),
                metadata.validationPolicyVersion(), metadata.strategyDataDigest());
    }

    private static RawSource raw(StrategyInput input) {
        return switch (input) {
            case LlmFirstInput llm -> new RawSource(empty(llm.title()), empty(llm.description()));
            case HybridInput hybrid -> new RawSource(empty(hybrid.title()), empty(hybrid.description()));
        };
    }

    private static String empty(String value) {
        return value == null ? "" : value;
    }

    private static String digest(String text) {
        try {
            return "sha256:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", impossible);
        }
    }

    private record RawSource(String title, String description) {}
}
