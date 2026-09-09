package com.jobcopilot.intelligence;

import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;
import java.util.List;
import java.util.Set;
import javax.tools.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static com.jobcopilot.intelligence.JobIntelligenceTestSupport.*;
import static com.jobcopilot.intelligence.JobIntelligenceResult.*;
import static org.junit.jupiter.api.Assertions.*;

class JobIntelligenceBoundaryQaTest {
    private final JsonMapper json = JsonMapper.builder().build();
    @TempDir Path classes;

    @Test void externalCallerCannotBypassRunnerAssemblyOrReachInternalValidator() throws Exception {
        assertTrue(compiles("return new JobIntelligenceRunner(new JobIntelligencePrompt(), model).run(new JobIntelligenceRunner.Request(new JobIntelligencePrompt.LlmFirstInput(\"T\",\"D\"), settings));"));
        assertFalse(compiles("return new JobIntelligenceRunner(new JobIntelligencePrompt(), model).run(input);"));
        assertFalse(compiles("return new JobIntelligenceValidator().validate(null, \"canonical\", \"canonical\");"));
        assertFalse(compiles("return new JobIntelligenceDecoder().decode(\"{}\");"));
        // Public result construction is possible; only runner-produced acceptance is guaranteed.
        assertTrue(compiles("return new JobIntelligenceResult.Accepted(intelligence, metadata);"));
    }
    private boolean compiles(String body) throws Exception {
        String source = "package qa.external; import com.jobcopilot.intelligence.*; class Probe { Object run(JobIntelligenceModel model, JobIntelligenceModel.ModelInput input, JobIntelligencePrompt.ExecutionSettings settings, JobIntelligence intelligence, JobIntelligenceResult.AttemptMetadata metadata) { " + body + " }}";
        var unit = new SimpleJavaFileObject(URI.create("string:///qa/external/Probe.java"), JavaFileObject.Kind.SOURCE) {
            public CharSequence getCharContent(boolean ignored) { return source; }
        };
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var files = compiler.getStandardFileManager(diagnostics, null, null)) {
            return compiler.getTask(null, files, diagnostics, List.of("-proc:none", "--release", "21", "-classpath",
                    System.getProperty("java.class.path"), "-d", classes.toString()), null, List.of(unit)).call();
        }
    }
    @Test void intelligenceHasNoProductionWiringOrForbiddenDependencies() throws Exception {
        Path root = Path.of("src/main/java");
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(path);
                if (path.toString().contains("intelligence")) {
                    for (String forbidden : List.of("com.jobcopilot.job.", "com.jobcopilot.resume.", "com.jobcopilot.matching.", "org.springframework", "jakarta.persistence"))
                        assertFalse(source.contains(forbidden), path + ": " + forbidden);
                } else {
                    assertFalse(source.contains("com.jobcopilot.intelligence"), path.toString());
                    assertFalse(source.contains("JobIntelligenceRunner"), path.toString());
                }
            }
        }
    }
    @Test void allProviderOutcomesHaveExactCodesAndOneInvocation() {
        for (var outcome : JobIntelligenceModel.Outcome.values()) {
            var model = new ScriptedModel(new JobIntelligenceModel.ModelAttemptResult("{", outcome, "secret", null, null, null, null));
            var result = assertInstanceOf(Failed.class, runner(model).run(request(new JobIntelligencePrompt.LlmFirstInput("R", "D"))));
            if (outcome == JobIntelligenceModel.Outcome.COMPLETED) {
                assertEquals(Stage.DECODE, result.failure().stage());
                assertEquals(Code.MALFORMED_JSON, result.failure().code());
            } else {
                assertEquals(Stage.EXECUTION, result.failure().stage());
                assertEquals("PROVIDER_" + outcome.name(), result.failure().code().name());
            }
            assertEquals(1, model.invocations());
            assertFalse(result.toString().contains("secret"));
        }
        for (String candidate : new String[] {null, "", " "}) {
            var model = new ScriptedModel(candidate);
            var result = assertInstanceOf(Failed.class, runner(model).run(request(new JobIntelligencePrompt.LlmFirstInput("R", "D"))));
            assertEquals(Stage.DECODE, result.failure().stage());
            assertEquals(1, model.invocations());
        }
    }
    @ParameterizedTest @ValueSource(strings = {"must have Java.", "Must have Java!", "Must  have Java.", "Must have\nJava.", "Must have\r\nJava."})
    void exactRawQuoteIsNotNormalized(String raw) {
        var result = assertInstanceOf(Failed.class, JobIntelligenceAdversarialQaTest.runJson(javaRequired(), raw));
        assertEquals(Code.EVIDENCE_QUOTE_MISMATCH, result.failure().code());
    }
    @Test void unusedEvidenceAndUncertaintyReferencesAreChecked() {
        ObjectNode node = (ObjectNode) json.readTree(javaRequired());
        var evidence = (tools.jackson.databind.node.ArrayNode) node.get("evidence");
        evidence.add(json.readTree("{\"id\":\"unused\",\"source\":\"TITLE\",\"quote\":\"fabricated\"}"));
        assertEquals(Code.EVIDENCE_QUOTE_MISMATCH, assertInstanceOf(Failed.class,
                JobIntelligenceAdversarialQaTest.runJson(node.toString(), "Must have Java.")).failure().code());
        evidence.remove(1);
        var uncertainties = (tools.jackson.databind.node.ArrayNode) node.get("uncertainties");
        uncertainties.add(json.readTree("{\"code\":\"AMBIGUOUS_EXPERIENCE\",\"target\":\"anything\",\"evidenceIds\":[\"missing\"]}"));
        assertEquals(Code.DANGLING_EVIDENCE_ID, assertInstanceOf(Failed.class,
                JobIntelligenceAdversarialQaTest.runJson(node.toString(), "Must have Java.")).failure().code());
    }
    @Test void repeatedQuotesAndRepeatedReferencesArePermitted() {
        assertInstanceOf(Accepted.class, JobIntelligenceAdversarialQaTest.runJson(
                javaRequired().replace("[\"e1\"]", "[\"e1\",\"e1\",\"e1\"]"), "Must have Java. Must have Java."));
    }
    @Test void canonicalChangesAffectOnlyHybridInputDigestNotGrounding() {
        var prompt = new JobIntelligencePrompt();
        var model = new ScriptedModel(javaRequired());
        var first = runner(model).run(request(new JobIntelligencePrompt.HybridInput("Role", "No technology specified",
                new JobIntelligencePrompt.CanonicalRequirements(List.of("Java"), List.of(), null))));
        var second = runner(model).run(request(new JobIntelligencePrompt.HybridInput("Role", "No technology specified",
                new JobIntelligencePrompt.CanonicalRequirements(List.of("Kafka"), List.of(), null))));
        assertEquals(((Failed) first).failure(), ((Failed) second).failure());
        assertNotEquals(first.metadata().strategyDataDigest(), second.metadata().strategyDataDigest());
        var input = new JobIntelligencePrompt.LlmFirstInput("Role", "Must have Java.");
        var before = runner(model).run(request(input));
        prompt.assemble(new JobIntelligencePrompt.HybridInput("Role", "Must have Java.",
                new JobIntelligencePrompt.CanonicalRequirements(List.of("secret"), List.of(), null)), settings());
        var after = runner(model).run(request(input));
        assertEquals(before.metadata().strategyDataDigest(), after.metadata().strategyDataDigest());
        assertEquals(before.metadata().promptIdentity(), after.metadata().promptIdentity());
    }
    @Test void finiteTokenMatrixDoesNotInventAliases() {
        for (String[] pair : List.of(new String[]{"JavaScript", "Java"}, new String[]{"C++", "C"}, new String[]{"C#", "C"},
                new String[]{"PostgreSQL", "SQL"}, new String[]{"go to work", "Go"}, new String[]{"Required", "R"}))
            assertFalse(TechnologyTokens.contains(pair[0], pair[1]));
        // Existing lexical policy, not a proof that these words denote a technology in context.
        for (String[] pair : List.of(new String[]{"Spring Boot", "Spring"}, new String[]{"Node.js", "Node"}, new String[]{"React Native", "React"}))
            assertTrue(TechnologyTokens.contains(pair[0], pair[1]));
    }
    @Test void finiteSeniorityPatternsAndUnsupportedNumerals() {
        assertFalse(InterpretationSupport.seniority(JobIntelligence.Seniority.ENTRY, List.of("Software Engineer I")));
        assertFalse(InterpretationSupport.seniority(JobIntelligence.Seniority.MID, List.of("Software Engineer II")));
        assertTrue(InterpretationSupport.seniority(JobIntelligence.Seniority.SENIOR, List.of("Senior Software Engineer")));
        assertTrue(InterpretationSupport.seniority(JobIntelligence.Seniority.STAFF_PLUS, List.of("Staff Engineer")));
        assertTrue(InterpretationSupport.seniority(JobIntelligence.Seniority.STAFF_PLUS, List.of("Principal Engineer")));
        assertFalse(InterpretationSupport.seniority(JobIntelligence.Seniority.STAFF_PLUS, List.of("staffing")));
        assertTrue(InterpretationSupport.role(JobIntelligence.Role.BACKEND, List.of("Backend Developer")));
        assertTrue(InterpretationSupport.role(JobIntelligence.Role.FULL_STACK, List.of("Full Stack Engineer")));
        assertFalse(InterpretationSupport.role(JobIntelligence.Role.OTHER, List.of("Engineer")));
    }
}
