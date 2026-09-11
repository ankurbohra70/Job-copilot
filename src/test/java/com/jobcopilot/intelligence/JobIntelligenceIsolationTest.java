package com.jobcopilot.intelligence;

import com.jobcopilot.job.JobExtractionBaseline;
import com.jobcopilot.job.JobMatchingSnapshot;
import com.jobcopilot.job.JobRequirementExtractor;
import com.jobcopilot.job.dto.JobRequirementsResponse;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import static com.jobcopilot.intelligence.JobIntelligenceTestSupport.ScriptedModel;
import static com.jobcopilot.intelligence.JobIntelligenceTestSupport.javaRequired;
import static com.jobcopilot.intelligence.JobIntelligenceTestSupport.runner;
import static com.jobcopilot.intelligence.JobIntelligencePrompt.*;
import static org.junit.jupiter.api.Assertions.*;

class JobIntelligenceIsolationTest {
    private final JobIntelligencePrompt prompt = new JobIntelligencePrompt();

    @Test void changingCanonicalRequirementsCannotChangeCompleteLlmFirstRequest() {
        var first = snapshot(List.of("CANONICAL_SENTINEL_A"));
        var second = snapshot(List.of("CANONICAL_SENTINEL_B", "java"));
        var before = request(first);
        // Constructing Hybrid context in the same process must not contaminate subsequent LLM-first calls.
        prompt.assemble(new HybridInput(second.title(), second.description(), new CanonicalRequirements(
                second.requirements().requiredSkills(), second.requirements().preferredSkills(), null)), JobIntelligencePromptTest.settings());
        assertEquals(before, request(second));
        assertFalse(before.strategyData().contains("CANONICAL_SENTINEL"));
    }

    @Test void independentBaselineExecutionCannotAlterLlmFirstConstruction() {
        var source = snapshot(List.of("CANONICAL_SENTINEL"));
        var before = request(source);
        var baseline = new JobExtractionBaseline(new JobRequirementExtractor());
        assertEquals(JobExtractionBaseline.Status.SUCCESS, baseline.capture(source.description()).status());
        assertEquals(before, request(source));
        assertEquals(JobExtractionBaseline.Status.UNAVAILABLE, baseline.capture(" ").status());
        assertEquals(before, request(source));
    }

    @Test void canonicalCanaryCannotReachLlmFirstModelBoundary() {
        String canary = "JC007_CANONICAL_ONLY_CANARY_9F3A";
        var source = snapshot(List.of(canary));
        var model = new ScriptedModel(input -> {
            assertTrue(Stream.of(input.model(), input.instructions(), input.strategyData(), input.promptVersion(),
                            input.schemaVersion(), input.outputSchema())
                    .noneMatch(value -> value != null && value.contains(canary)));
            return ScriptedModel.completed(javaRequired());
        });
        var result = runner(model).run(new JobIntelligenceRunner.Request(
                new LlmFirstInput(source.title(), source.description()), JobIntelligencePromptTest.settings()));
        assertInstanceOf(JobIntelligenceResult.Accepted.class, result);
        assertEquals(JobIntelligenceResult.Strategy.LLM_FIRST, result.metadata().strategy());
        assertEquals(1, model.invocations());
    }

    @Test void strategyContractsExposeOnlyPermittedData() {
        assertTrue(StrategyInput.class.isSealed());
        assertEquals(Set.of(LlmFirstInput.class, HybridInput.class), Set.of(StrategyInput.class.getPermittedSubclasses()));
        assertEquals(List.of("title", "description"), Arrays.stream(LlmFirstInput.class.getRecordComponents()).map(c -> c.getName()).toList());
        assertTrue(Arrays.stream(LlmFirstInput.class.getRecordComponents()).allMatch(c -> c.getType() == String.class));
        // Inspect the reachable public data contract, not private serialization machinery.
        assertSafeContract(StrategyInput.class, new HashSet<>());
        for (var accessor : JobIntelligenceModel.ModelInput.class.getDeclaredMethods()) {
            assertEquals(0, accessor.getParameterCount());
            assertSafeContract(accessor.getGenericReturnType(), new HashSet<>());
        }
        for (var method : JobIntelligencePrompt.class.getDeclaredMethods()) {
            if (java.lang.reflect.Modifier.isPublic(method.getModifiers())) {
                for (Type parameter : method.getGenericParameterTypes()) assertSafeContract(parameter, new HashSet<>());
            }
        }
    }

    private static void assertSafeContract(Type type, Set<Type> visited) {
        if (!visited.add(type)) return;
        if (type instanceof ParameterizedType parameterized) {
            assertSafeContract(parameterized.getRawType(), visited);
            for (Type argument : parameterized.getActualTypeArguments()) assertSafeContract(argument, visited);
        } else if (type instanceof Class<?> clazz) {
            assertFalse(clazz.getName().startsWith("com.jobcopilot.job."), clazz.getName());
            assertFalse(clazz.getName().startsWith("com.jobcopilot.resume."), clazz.getName());
            assertFalse(clazz.getName().startsWith("com.jobcopilot.matching."), clazz.getName());
            assertNotEquals(Object.class, clazz);
            assertFalse(java.util.Map.class.isAssignableFrom(clazz), clazz.getName());
            if (clazz.isRecord()) for (var component : clazz.getRecordComponents()) assertSafeContract(component.getGenericType(), visited);
            if (clazz.isSealed()) for (var subtype : clazz.getPermittedSubclasses()) assertSafeContract(subtype, visited);
        }
    }
    private JobIntelligenceModel.ModelInput request(JobMatchingSnapshot snapshot) {
        return prompt.assemble(new LlmFirstInput(snapshot.title(), snapshot.description()), JobIntelligencePromptTest.settings());
    }
    private static JobMatchingSnapshot snapshot(List<String> skills) {
        return new JobMatchingSnapshot(1L, "Engineer", "Must have Java.", "Remote",
                new JobRequirementsResponse(skills, List.of(), null), LocalDateTime.of(2026, 9, 9, 12, 0));
    }
}
