package com.jobcopilot.intelligence;

import com.jobcopilot.job.JobExtractionBaseline;
import com.jobcopilot.job.JobRequirementExtractor;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static com.jobcopilot.intelligence.JobIntelligencePrompt.*;
import static com.jobcopilot.intelligence.JobIntelligenceTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class JobIntelligencePhase3QaTest {
    @ParameterizedTest @ValueSource(strings = {"scan", "tree", "binding", "configuration", "constructor"})
    void internalDecoderDefectsPropagateThroughRunner(String boundary) throws Exception {
        var model = new ScriptedModel(javaRequired());
        var runner = runner(model);
        var decoderField = JobIntelligenceRunner.class.getDeclaredField("decoder");
        decoderField.setAccessible(true);
        var decoder = decoderField.get(runner);
        var mapperField = JobIntelligenceDecoder.class.getDeclaredField("mapper");
        mapperField.setAccessible(true);
        var mapper = spy((JsonMapper) mapperField.get(decoder));
        RuntimeException defect = switch (boundary) {
            case "configuration" -> tools.jackson.databind.exc.InvalidDefinitionException.from(
                    (tools.jackson.core.JsonParser) null, "broken DTO configuration", mapper.constructType(JobIntelligenceModel.Output.class));
            case "constructor" -> tools.jackson.databind.exc.ValueInstantiationException.from(
                    null, "broken DTO constructor", mapper.constructType(JobIntelligenceModel.Output.class), new IllegalStateException("defect"));
            default -> new IllegalStateException("internal decoder defect");
        };
        switch (boundary) {
            case "scan" -> doThrow(defect).when(mapper).createParser(anyString());
            case "tree" -> doThrow(defect).when(mapper).readTree(anyString());
            case "binding", "configuration", "constructor" -> doThrow(defect).when(mapper).treeToValue(any(JsonNode.class), eq(JobIntelligenceModel.Output.class));
            default -> throw new AssertionError(boundary);
        }
        // Test-only fault injection leaves production construction and visibility unchanged.
        mapperField.set(decoder, mapper);
        assertSame(defect, assertThrows(RuntimeException.class,
                () -> runner.run(request(new LlmFirstInput("Engineer", "Must have Java.")))));
        assertEquals(1, model.invocations());
    }

    @ParameterizedTest @ValueSource(strings = {"1e9999999999", "1e-9999999999", "1e2147483647", "1e-2147483648"})
    void adversarialNumbersRemainTypedDecodeFailures(String number) {
        var model = new ScriptedModel(withExperience("1 year", number, "YEARS", "REQUIRED", "OVERALL", false, "Must have 1 year."));
        var failed = assertInstanceOf(JobIntelligenceResult.Failed.class,
                runner(model).run(request(new LlmFirstInput("Engineer", "Must have 1 year."))));
        assertEquals(JobIntelligenceResult.Stage.DECODE, failed.failure().stage());
        assertEquals(1, model.invocations());
    }

    @Test void hybridCanonicalSkillCannotSubstituteForMatchingRawEvidence() {
        var model = new ScriptedModel(javaRequired().replace("Must have Java.", "Must have Python."));
        var failed = assertInstanceOf(JobIntelligenceResult.Failed.class, runner(model).run(request(
                new HybridInput("Engineer", "Must have Python.", new CanonicalRequirements(List.of("Java"), List.of(), null)))));
        assertEquals(JobIntelligenceResult.Stage.VALIDATION, failed.failure().stage());
        assertEquals(JobIntelligenceResult.Code.FACT_NOT_GROUNDED, failed.failure().code());
        assertEquals(1, model.invocations());
    }

    @Test void unavailableBaselineForSameSourceDoesNotGateEitherStrategy() {
        String description = " ";
        var baseline = new JobExtractionBaseline(new JobRequirementExtractor());
        assertEquals(JobExtractionBaseline.Status.UNAVAILABLE, baseline.capture(description).status());
        String candidate = javaRequired().replace("\"DESCRIPTION\"", "\"TITLE\"");
        for (StrategyInput input : List.of(new LlmFirstInput("Must have Java.", description),
                new HybridInput("Must have Java.", description, new CanonicalRequirements(List.of("Java"), List.of(), null)))) {
            var model = new ScriptedModel(candidate);
            assertInstanceOf(JobIntelligenceResult.Accepted.class, runner(model).run(request(input)));
            assertEquals(1, model.invocations());
            assertEquals(JobExtractionBaseline.Status.UNAVAILABLE, baseline.capture(description).status());
        }
    }
}
