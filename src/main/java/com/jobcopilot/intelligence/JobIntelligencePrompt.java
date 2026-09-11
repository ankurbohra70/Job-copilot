package com.jobcopilot.intelligence;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import tools.jackson.databind.json.JsonMapper;

/** Pure prompt assembly. Domain snapshots and independent baselines never enter this boundary. */
public final class JobIntelligencePrompt {
    public static final String SCHEMA_VERSION = "job-intelligence-output-v1";
    private final JsonMapper json = JsonMapper.builder().build();
    private final String common = resource("prompts/v1/common.txt");
    private final String hybrid = resource("prompts/v1/hybrid.txt");
    private final String llmFirst = resource("prompts/v1/llm-first.txt");
    private final String schema = resource("schemas/v1/model-output.schema.json");

    public sealed interface StrategyInput permits LlmFirstInput, HybridInput {}
    public record LlmFirstInput(String title, String description) implements StrategyInput {}
    public record HybridInput(String title, String description, CanonicalRequirements canonicalRequirements)
            implements StrategyInput {
        public HybridInput { Objects.requireNonNull(canonicalRequirements); }
    }
    public record CanonicalRequirements(List<String> requiredSkills, List<String> preferredSkills,
            BigDecimal minYearsExperience) {
        public CanonicalRequirements { requiredSkills = List.copyOf(requiredSkills); preferredSkills = List.copyOf(preferredSkills); }
    }
    public record ExecutionSettings(String model, JobIntelligenceModel.GenerationSettings generationSettings, Duration remainingTimeout) {
        public ExecutionSettings { Objects.requireNonNull(generationSettings); }
    }

    public JobIntelligenceModel.ModelInput assemble(StrategyInput input, ExecutionSettings settings) {
        // Concrete records have no strategy discriminator or broad context to accidentally serialize.
        String instruction;
        String version;
        switch (input) {
            case LlmFirstInput ignored -> { instruction = llmFirst; version = "llm-first-v1"; }
            case HybridInput ignored -> { instruction = hybrid; version = "hybrid-enrichment-v1"; }
        }
        String instructions = common + "\n" + instruction;
        return new AssembledInput(new Content(settings.model(), instructions,
                json.writeValueAsString(input), identity(version, instructions), identity(SCHEMA_VERSION, schema), schema,
                settings.generationSettings(), settings.remainingTimeout()));
    }

    // Refresh only the execution budget; preserve the already-assembled content and its identities.
    static JobIntelligenceModel.ModelInput withRemainingTimeout(JobIntelligenceModel.ModelInput input, Duration timeout) {
        return new AssembledInput(new Content(input.model(), input.instructions(), input.strategyData(),
                input.promptVersion(), input.schemaVersion(), input.outputSchema(), input.generationSettings(), timeout));
    }

    // Package visibility permits the sealed interface to name this class, not callers to construct it.
    static final class AssembledInput implements JobIntelligenceModel.ModelInput {
        private final Content content;
        private AssembledInput(Content content) { this.content = content; }
        public String model() { return content.model(); }
        public String instructions() { return content.instructions(); }
        public String strategyData() { return content.strategyData(); }
        public String promptVersion() { return content.promptVersion(); }
        public String schemaVersion() { return content.schemaVersion(); }
        public String outputSchema() { return content.outputSchema(); }
        public JobIntelligenceModel.GenerationSettings generationSettings() { return content.generationSettings(); }
        public Duration remainingTimeout() { return content.remainingTimeout(); }
        @Override public boolean equals(Object other) {
            return other instanceof AssembledInput input && content.equals(input.content);
        }
        @Override public int hashCode() { return content.hashCode(); }
    }

    private record Content(String model, String instructions, String strategyData,
            String promptVersion, String schemaVersion, String outputSchema,
            JobIntelligenceModel.GenerationSettings generationSettings, Duration remainingTimeout) {}

    private static String identity(String version, String text) {
        try {
            return version + ":sha256:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", impossible);
        }
    }

    private static String resource(String path) {
        try (var stream = JobIntelligencePrompt.class.getResourceAsStream("/job-intelligence/" + path)) {
            if (stream == null) throw new IllegalStateException("Missing job intelligence resource: " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) { throw new UncheckedIOException(exception); }
    }
}
