package com.jobcopilot.intelligence;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import static com.jobcopilot.intelligence.JobIntelligencePrompt.*;
import static org.junit.jupiter.api.Assertions.*;

class JobIntelligenceRequestBoundaryTest {
    @Test void requestContractIsClosedAndItsImplementationsCannotBeConstructedByPackagePeers() {
        Class<?> contract = JobIntelligenceModel.ModelInput.class;
        assertTrue(contract.isInterface());
        assertTrue(contract.isSealed());
        for (Class<?> implementation : contract.getPermittedSubclasses()) {
            assertTrue(Modifier.isFinal(implementation.getModifiers()));
            // Constructor accessibility is part of the isolation contract; signatures/storage are not.
            assertTrue(Arrays.stream(implementation.getDeclaredConstructors())
                    .allMatch(c -> Modifier.isPrivate(c.getModifiers())));
            assertFalse(Arrays.stream(implementation.getDeclaredMethods()).anyMatch(m ->
                    !Modifier.isPrivate(m.getModifiers())
                            && contract.isAssignableFrom(m.getReturnType())));
        }
        for (var method : contract.getDeclaredMethods()) {
            assertFalse(Modifier.isStatic(method.getModifiers()));
            assertEquals(0, method.getParameterCount(), "Request boundary must be read-only");
        }
        var factories = Arrays.stream(JobIntelligencePrompt.class.getDeclaredMethods())
                .filter(m -> !Modifier.isPrivate(m.getModifiers()) && contract.isAssignableFrom(m.getReturnType())).toList();
        assertEquals(2, factories.size(), "Only assembly and content-preserving budget refresh are permitted");
        var assembly = factories.stream().filter(m -> m.getName().equals("assemble")).findFirst().orElseThrow();
        assertEquals(List.of(StrategyInput.class, ExecutionSettings.class), List.of(assembly.getParameterTypes()));
        var refresh = factories.stream().filter(m -> m.getName().equals("withRemainingTimeout")).findFirst().orElseThrow();
        assertFalse(Modifier.isPublic(refresh.getModifiers()));
        assertEquals(List.of(contract, Duration.class), List.of(refresh.getParameterTypes()));
        assertFalse(Arrays.stream(JobIntelligenceModel.class.getDeclaredMethods())
                .anyMatch(m -> contract.isAssignableFrom(m.getReturnType())));
    }

    @ParameterizedTest
    @ValueSource(strings = {"external.application", "com.jobcopilot.intelligence"})
    void applicationAndPackagePeersCanOnlyUseApprovedAssembly(String packageName) throws Exception {
        assertTrue(compiles(packageName, "return new JobIntelligencePrompt().assemble(new LlmFirstInput(\"Title\", \"Description\"), "
                + "new ExecutionSettings(\"model\", new GenerationSettings(null, 6000), Duration.ofSeconds(30)));"));
    }

    @ParameterizedTest
    @CsvSource({
        "external.application, strategyData", "com.jobcopilot.intelligence, strategyData",
        "external.application, instructions", "com.jobcopilot.intelligence, instructions",
        "external.application, outputSchema", "com.jobcopilot.intelligence, outputSchema",
        "external.application, promptVersion", "com.jobcopilot.intelligence, promptVersion",
        "external.application, schemaVersion", "com.jobcopilot.intelligence, schemaVersion"
    })
    void callersCannotConstructRequestsWithSubstitutedContentOrIdentities(String packageName, String field) throws Exception {
        String body = "var good = new JobIntelligencePrompt().assemble(new LlmFirstInput(\"Title\", \"Description\"), "
                + "new ExecutionSettings(\"model\", new GenerationSettings(null, 6000), Duration.ofSeconds(30)));\n";
        List<String> fields = List.of("model", "instructions", "strategyData", "promptVersion", "schemaVersion", "outputSchema", "generationSettings", "remainingTimeout");
        String arguments = fields.stream().map(name -> name.equals(field) ? "\"CUSTOM_CANONICAL_OR_BASELINE_CONTENT\"" : "good." + name + "()")
                .collect(Collectors.joining(", "));
        assertFalse(compiles(packageName, body + "return new ModelInput(" + arguments + ");"));
    }

    @ParameterizedTest @ValueSource(strings = {"canonicalRequirements", "jc005Output", "metadata", "unknown"})
    void generationSettingsHaveNoKeyValueConstructor(String key) throws Exception {
        assertFalse(compiles("external.application", "return new JobIntelligencePrompt().assemble(new LlmFirstInput(\"Title\", \"Description\"), "
                + "new ExecutionSettings(\"model\", java.util.Map.of(\"" + key + "\", \"SENTINEL\"), Duration.ofSeconds(30)));"));
    }

    @Test void generationSettingsAreOnlyClosedTypedControls() {
        var components = JobIntelligenceModel.GenerationSettings.class.getRecordComponents();
        assertEquals(Set.of("temperature", "maxOutputTokens"), Arrays.stream(components).map(c -> c.getName()).collect(Collectors.toSet()));
        assertEquals(Double.class, Arrays.stream(components).filter(c -> c.getName().equals("temperature")).findFirst().orElseThrow().getType());
        assertEquals(int.class, Arrays.stream(components).filter(c -> c.getName().equals("maxOutputTokens")).findFirst().orElseThrow().getType());
        var controls = new JobIntelligenceModel.GenerationSettings(null, 6000);
        var request = new JobIntelligencePrompt().assemble(new LlmFirstInput("Title", "Description"),
                new ExecutionSettings("model", controls, Duration.ofSeconds(30)));
        assertEquals(controls, request.generationSettings());
        assertNull(request.generationSettings().temperature());
        assertEquals(6000, request.generationSettings().maxOutputTokens());
    }

    @ParameterizedTest @ValueSource(strings = {"llm-first", "hybrid"})
    void identitiesAreCoupledToExactAssembledResources(String strategy) throws Exception {
        StrategyInput input = strategy.equals("llm-first") ? new LlmFirstInput("Title", "Description")
                : new HybridInput("Title", "Description", new CanonicalRequirements(List.of(), List.of(), null));
        var request = new JobIntelligencePrompt().assemble(input, JobIntelligencePromptTest.settings());
        String instructions = resource("prompts/v1/common.txt") + "\n" + resource("prompts/v1/" + strategy + ".txt");
        String schema = resource("schemas/v1/model-output.schema.json");
        assertEquals(instructions, request.instructions());
        assertEquals(schema, request.outputSchema());
        String promptLabel = strategy.equals("llm-first") ? "llm-first-v1" : "hybrid-enrichment-v1";
        assertEquals(promptLabel + ":sha256:" + sha256(instructions), request.promptVersion());
        assertEquals("job-intelligence-output-v1:sha256:" + sha256(schema), request.schemaVersion());
        assertNotEquals(promptLabel + ":sha256:" + sha256(instructions + " replaced"), request.promptVersion());
        assertNotEquals("job-intelligence-output-v1:sha256:" + sha256("{}"), request.schemaVersion());
    }

    private static String resource(String path) throws Exception {
        try (var stream = JobIntelligenceRequestBoundaryTest.class.getResourceAsStream("/job-intelligence/" + path)) {
            assertNotNull(stream);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    private static String sha256(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    /** Compile application callers in memory, including peers in the production package. */
    private static boolean compiles(String packageName, String body) throws Exception {
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "Contract tests require the project's Java 21 JDK");
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        String source = "package " + packageName + ";\nimport com.jobcopilot.intelligence.*;\n"
                + "import com.jobcopilot.intelligence.JobIntelligenceModel.*;\n"
                + "import com.jobcopilot.intelligence.JobIntelligencePrompt.*;\nimport java.time.Duration;\n"
                + "class Probe { ModelInput build() { " + body + " } }";
        var unit = new SimpleJavaFileObject(URI.create("string:///" + packageName.replace('.', '/') + "/Probe.java"), JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) { return source; }
        };
        try (var standard = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8);
                var files = new ForwardingJavaFileManager<>(standard) {
                    @Override public JavaFileObject getJavaFileForOutput(Location location, String className, JavaFileObject.Kind kind, FileObject sibling) {
                        return new SimpleJavaFileObject(URI.create("memory:///" + className.replace('.', '/') + kind.extension), kind) {
                            @Override public OutputStream openOutputStream() { return new ByteArrayOutputStream(); }
                        };
                    }
                }) {
            boolean success = compiler.getTask(null, files, diagnostics,
                    List.of("-proc:none", "--release", "21", "-classpath", System.getProperty("java.class.path")), null, List.of(unit)).call();
            assertFalse(diagnostics.getDiagnostics().stream().anyMatch(d -> d.getKind() == Diagnostic.Kind.ERROR
                    && (d.getCode().contains("doesnt.exist") || d.getCode().contains("cant.access"))), diagnostics.getDiagnostics().toString());
            return success;
        }
    }
}
