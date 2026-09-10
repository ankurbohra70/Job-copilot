package com.jobcopilot.intelligence.openai;

import com.jobcopilot.intelligence.JobIntelligenceModel;
import com.jobcopilot.intelligence.JobIntelligencePrompt;
import com.jobcopilot.intelligence.JobIntelligenceResult;
import com.jobcopilot.intelligence.JobIntelligenceRunner;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.LogLevel;
import com.openai.core.Timeout;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OpenAiJobIntelligenceModelTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicReference<String> method = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private HttpServer server;
    private OpenAIClient client;

    @AfterEach void close() {
        if (client != null) client.close();
        if (server != null) server.stop(0);
    }

    @Test void requestMappingPreservesAssemblyIsolationHybridNeutralityAndSchema() throws Exception {
        start(exchange -> respond(exchange, 200, completed("{\"untrusted\":true}", true)));
        String canonicalCanary = "CANONICAL_SECRET_6d6404";
        var llm = analyze(new JobIntelligencePrompt.LlmFirstInput("Backend", "Must have Java."), Duration.ofSeconds(3));
        assertEquals(JobIntelligenceModel.Outcome.COMPLETED, llm.outcome());
        assertEquals(1, requests.get());
        JsonNode sent = JSON.readTree(body.get());
        var prompt = new JobIntelligencePrompt();
        var expected = prompt.assemble(new JobIntelligencePrompt.LlmFirstInput("Backend", "Must have Java."), settings(Duration.ofSeconds(3)));
        assertEquals("test-model", sent.get("model").asText());
        assertEquals("POST", method.get());
        assertEquals("Bearer test-secret-key", authorization.get());
        assertEquals(expected.instructions(), sent.get("instructions").asText());
        assertEquals(expected.strategyData(), sent.get("input").asText());
        assertEquals(0.2, sent.get("temperature").asDouble());
        assertEquals(600, sent.get("max_output_tokens").asInt());
        assertFalse(sent.get("background").asBoolean());
        assertFalse(sent.get("store").asBoolean());
        assertFalse(sent.has("stream"));
        assertTrue(sent.at("/text/format/strict").asBoolean());
        assertEquals("json_schema", sent.at("/text/format/type").asText());
        assertEquals(JSON.readTree(expected.outputSchema()), sent.at("/text/format/schema"));
        assertFalse(body.get().contains(canonicalCanary));
        assertFalse(body.get().contains("promptVersion"));
        assertFalse(body.get().contains("schemaVersion"));

        requests.set(0);
        String hybridDescription = "Must have Java.";
        var hybridInput = new JobIntelligencePrompt.HybridInput("Backend", hybridDescription,
                new JobIntelligencePrompt.CanonicalRequirements(List.of(canonicalCanary), List.of(), null));
        var expectedHybrid = prompt.assemble(hybridInput, settings(Duration.ofSeconds(3)));
        analyze(hybridInput, Duration.ofSeconds(3));
        JsonNode hybridSent = JSON.readTree(body.get());
        assertEquals(expectedHybrid.strategyData(), hybridSent.get("input").asText());
        assertEquals(1, occurrences(body.get(), canonicalCanary));
        assertEquals(1, requests.get());
    }

    @Test void completedResponseMapsRawCandidateRequestIdAndUsage() throws Exception {
        start(exchange -> respond(exchange, 200, completed("{\"raw\":\"candidate\"}", true)));
        var result = analyze(new JobIntelligencePrompt.LlmFirstInput("T", "D"), Duration.ofSeconds(3));
        assertEquals(JobIntelligenceModel.Outcome.COMPLETED, result.outcome());
        assertEquals("{\"raw\":\"candidate\"}", result.candidateJson());
        assertEquals("returned-model", result.returnedModel());
        assertEquals("req_loopback_1", result.providerRequestId());
        assertEquals(new JobIntelligenceModel.Usage(11L, 7L, 3L), result.usage());
        assertNotNull(result.latency());
        assertFalse(result.latency().isNegative());
        assertEquals(1, requests.get());
    }

    @Test void refusalAndIncompleteNeverReturnCandidate() throws Exception {
        start(exchange -> respond(exchange, 200, refusal()));
        var refused = analyze(new JobIntelligencePrompt.LlmFirstInput("T", "D"), Duration.ofSeconds(3));
        assertEquals(JobIntelligenceModel.Outcome.REFUSED, refused.outcome());
        assertNull(refused.candidateJson());
        assertEquals(1, requests.get());
        close(); server = null; client = null; requests.set(0);
        start(exchange -> respond(exchange, 200, incomplete()));
        var incomplete = analyze(new JobIntelligencePrompt.LlmFirstInput("T", "D"), Duration.ofSeconds(3));
        assertEquals(JobIntelligenceModel.Outcome.INCOMPLETE, incomplete.outcome());
        assertNull(incomplete.candidateJson());
        assertEquals(1, requests.get());
    }

    @ParameterizedTest @ValueSource(ints = {408, 409, 429, 500, 502, 503, 504})
    void transientHttpFailureIsClassifiedWithoutRetry(int status) throws Exception {
        start(exchange -> {
            exchange.getResponseHeaders().add("Retry-After", "0");
            respond(exchange, status, error("PROVIDER_BODY_SECRET", status));
        });
        var result = analyze(new JobIntelligencePrompt.LlmFirstInput("T", "D"), Duration.ofSeconds(3));
        assertEquals(JobIntelligenceModel.Outcome.RETRYABLE_FAILURE, result.outcome());
        assertEquals("transient_http", result.failureCode());
        assertFalse(result.toString().contains("PROVIDER_BODY_SECRET"));
        assertEquals(1, requests.get());
    }

    @ParameterizedTest @ValueSource(ints = {400, 401, 403, 404, 422})
    void permanentHttpFailureIsSafeAndNotRetried(int status) throws Exception {
        start(exchange -> respond(exchange, status, error("PROVIDER_BODY_SECRET", status)));
        var result = analyze(new JobIntelligencePrompt.LlmFirstInput("T", "D"), Duration.ofSeconds(3));
        assertEquals(JobIntelligenceModel.Outcome.PERMANENT_FAILURE, result.outcome());
        assertEquals("request_rejected", result.failureCode());
        assertFalse(result.toString().contains("PROVIDER_BODY_SECRET"));
        assertEquals(1, requests.get());
    }

    @Test void malformedEnvelopeIsSafeAndNotRetried() throws Exception {
        start(exchange -> respond(exchange, 200, "{not-json"));
        var result = analyze(new JobIntelligencePrompt.LlmFirstInput("T", "D"), Duration.ofSeconds(3));
        assertEquals(JobIntelligenceModel.Outcome.PERMANENT_FAILURE, result.outcome());
        assertEquals("malformed_response", result.failureCode());
        assertEquals(1, requests.get());
    }

    @Test void unexpectedAdapterDefectEscapesProviderClassification() {
        client = mock(OpenAIClient.class);
        when(client.responses()).thenThrow(new IllegalStateException("adapter-defect"));
        var input = new JobIntelligencePrompt().assemble(new JobIntelligencePrompt.LlmFirstInput("T", "D"),
                settings(Duration.ofSeconds(1)));
        var failure = assertThrows(IllegalStateException.class,
                () -> new OpenAiJobIntelligenceModel(client).analyze(input));
        assertEquals("adapter-defect", failure.getMessage());
    }

    @Test void emptyCompletedOutputStillFlowsToExistingDecoder() throws Exception {
        start(exchange -> respond(exchange, 200, completed(null, false)));
        var prompt = new JobIntelligencePrompt();
        var runner = new JobIntelligenceRunner(prompt, new OpenAiJobIntelligenceModel(client));
        var result = assertInstanceOf(JobIntelligenceResult.Failed.class, runner.run(new JobIntelligenceRunner.Request(
                new JobIntelligencePrompt.LlmFirstInput("T", "D"), settings(Duration.ofSeconds(3)))));
        assertEquals(JobIntelligenceResult.Stage.DECODE, result.failure().stage());
        assertEquals(JobIntelligenceResult.Code.EMPTY_CANDIDATE, result.failure().code());
        assertEquals(JobIntelligenceModel.Outcome.COMPLETED, result.metadata().providerOutcome());
        assertEquals(1, requests.get());
    }

    @Test void disconnectMakesAtMostOneRequest() throws Exception {
        start(exchange -> exchange.close());
        var result = analyze(new JobIntelligencePrompt.LlmFirstInput("T", "D"), Duration.ofSeconds(2));
        assertEquals(JobIntelligenceModel.Outcome.RETRYABLE_FAILURE, result.outcome());
        assertEquals(1, requests.get());
    }

    @Test void stalledTransportTimesOutWithoutRetry() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        start(exchange -> {
            try { release.await(2, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        long start = System.nanoTime();
        try {
            var result = analyze(new JobIntelligencePrompt.LlmFirstInput("T", "D"), Duration.ofMillis(150));
            assertEquals(JobIntelligenceModel.Outcome.RETRYABLE_FAILURE, result.outcome());
            assertTrue(Duration.ofNanos(System.nanoTime() - start).compareTo(Duration.ofSeconds(2)) < 0);
            assertEquals(1, requests.get());
        } finally { release.countDown(); }
    }

    @ParameterizedTest @ValueSource(strings = {"usage", "input_tokens_details", "cached_tokens", "model", "request-id"})
    void missingMetadataDoesNotDiscardCompletedCandidate(String missing) throws Exception {
        var envelope = (tools.jackson.databind.node.ObjectNode) JSON.readTree(completed("{}", true));
        switch (missing) {
            case "usage", "model" -> envelope.remove(missing);
            case "input_tokens_details" -> ((tools.jackson.databind.node.ObjectNode) envelope.get("usage")).remove(missing);
            case "cached_tokens" -> ((tools.jackson.databind.node.ObjectNode) envelope.at("/usage/input_tokens_details")).remove(missing);
            default -> { }
        }
        start(exchange -> {
            byte[] bytes = envelope.toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        var result = analyze(new JobIntelligencePrompt.LlmFirstInput("T", "D"), Duration.ofSeconds(3));
        assertEquals(JobIntelligenceModel.Outcome.COMPLETED, result.outcome());
        assertEquals("{}", result.candidateJson());
        assertNull(result.providerRequestId()); // response ID must not impersonate HTTP request ID
        if (missing.equals("model")) assertNull(result.returnedModel());
        if (missing.equals("usage")) assertNull(result.usage());
        if (missing.equals("cached_tokens") || missing.equals("input_tokens_details")) {
            assertEquals(11L, result.usage().inputTokens());
            assertNull(result.usage().cachedInputTokens());
        }
        assertEquals(1, requests.get());
    }

    @ParameterizedTest @ValueSource(ints = {301, 302, 303, 307, 308, 401, 407})
    void redirectAndAuthenticationChallengesNeverFollowUp(int status) throws Exception {
        start(exchange -> {
            exchange.getResponseHeaders().add("Location", "http://127.0.0.1:" + server.getAddress().getPort() + "/responses");
            exchange.getResponseHeaders().add("WWW-Authenticate", "Basic realm=qa");
            exchange.getResponseHeaders().add("Proxy-Authenticate", "Basic realm=qa");
            respond(exchange, status, error("PRIVATE_CHALLENGE", status));
        });
        var result = analyze(new JobIntelligencePrompt.LlmFirstInput("T", "D"), Duration.ofSeconds(2));
        assertNotEquals(JobIntelligenceModel.Outcome.COMPLETED, result.outcome());
        assertFalse(result.toString().contains("PRIVATE_CHALLENGE"));
        assertEquals(1, requests.get());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void truncatedBodyDoesNotReconnect(boolean partial) throws Exception {
        start(exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, 1000);
            if (partial) exchange.getResponseBody().write("{\"id\":".getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
            exchange.close();
        });
        var result = analyze(new JobIntelligencePrompt.LlmFirstInput("T", "D"), Duration.ofMillis(400));
        assertNotEquals(JobIntelligenceModel.Outcome.COMPLETED, result.outcome());
        assertEquals(1, requests.get());
    }

    @Test void priorSuccessfulConnectionDoesNotEnableRecoveryPost() throws Exception {
        start(exchange -> {
            if (requests.get() == 1) respond(exchange, 200, completed("{}", true));
            else exchange.close();
        });
        assertEquals(JobIntelligenceModel.Outcome.COMPLETED,
                analyze(new JobIntelligencePrompt.LlmFirstInput("T", "D"), Duration.ofSeconds(2)).outcome());
        assertEquals(JobIntelligenceModel.Outcome.RETRYABLE_FAILURE,
                analyze(new JobIntelligencePrompt.LlmFirstInput("T", "D"), Duration.ofSeconds(2)).outcome());
        assertEquals(2, requests.get()); // one for each independent attempt
    }

    @ParameterizedTest @ValueSource(strings = {"reasoning-before", "reasoning-after", "multiple", "no-text", "refusal-and-text"})
    void outputVariantsNeverConcatenateCandidates(String variant) throws Exception {
        var root = (tools.jackson.databind.node.ObjectNode) JSON.readTree(completed("{}", true));
        var output = (tools.jackson.databind.node.ArrayNode) root.get("output");
        var message = output.get(0).deepCopy();
        var reasoning = JSON.readTree("{\"id\":\"rs_1\",\"type\":\"reasoning\",\"summary\":[]}");
        switch (variant) {
            case "reasoning-before" -> { output.removeAll(); output.add(reasoning); output.add(message); }
            case "reasoning-after" -> output.add(reasoning);
            case "multiple" -> output.add(message);
            case "no-text" -> { output.removeAll(); output.add(reasoning); }
            case "refusal-and-text" -> ((tools.jackson.databind.node.ArrayNode) output.at("/0/content"))
                    .add(JSON.readTree("{\"type\":\"refusal\",\"refusal\":\"PRIVATE_REFUSAL\"}"));
        }
        start(exchange -> respond(exchange, 200, root.toString()));
        var result = analyze(new JobIntelligencePrompt.LlmFirstInput("T", "D"), Duration.ofSeconds(3));
        if (variant.equals("multiple")) {
            assertEquals("multiple_output_candidates", result.failureCode());
            assertNull(result.candidateJson());
        } else if (variant.equals("refusal-and-text")) {
            assertEquals(JobIntelligenceModel.Outcome.REFUSED, result.outcome());
            assertNull(result.candidateJson());
        } else {
            assertEquals(JobIntelligenceModel.Outcome.COMPLETED, result.outcome());
            assertEquals(variant.equals("no-text") ? null : "{}", result.candidateJson());
        }
        assertEquals(1, requests.get());
    }

    @ParameterizedTest @ValueSource(strings = {"unknown", "missing", "duplicate", "malformed", "extreme", "trailing", "oversized", "empty"})
    void completedInvalidCandidateAlwaysReachesLocalDecoder(String variant) throws Exception {
        String valid = validCandidate();
        String candidate = switch (variant) {
            case "unknown" -> valid.replaceFirst("\\{", "{\"PRIVATE_UNKNOWN\":true,");
            case "missing" -> "{}";
            case "duplicate" -> valid.replaceFirst("\\{", "{\"facts\":{},");
            case "malformed" -> "{PRIVATE_CANDIDATE";
            case "extreme" -> "{\"facts\":1e99999999999999999999999}";
            case "trailing" -> valid + " {}";
            case "oversized" -> " ".repeat(262145);
            default -> "";
        };
        start(exchange -> respond(exchange, 200, completed(candidate, true)));
        var failed = assertInstanceOf(JobIntelligenceResult.Failed.class, run(
                new JobIntelligencePrompt.LlmFirstInput("PRIVATE_TITLE", "PRIVATE_DESCRIPTION"), Duration.ofSeconds(3)));
        assertEquals(JobIntelligenceResult.Stage.DECODE, failed.failure().stage());
        assertEquals(JobIntelligenceModel.Outcome.COMPLETED, failed.metadata().providerOutcome());
        assertFalse(failed.toString().contains("PRIVATE_"));
        assertEquals(1, requests.get());
    }

    @Test void canonicalStateCannotChangeLlmFirstWireContentAndCannotGroundHybridClaim() throws Exception {
        start(exchange -> respond(exchange, 200, completed(validCandidate(), true)));
        var prompt = new JobIntelligencePrompt();
        String canary = "JC007_PHASE4_CANONICAL_CANARY_A91D";
        var hybrid = new JobIntelligencePrompt.HybridInput("Engineer", "Must have Python.",
                new JobIntelligencePrompt.CanonicalRequirements(List.of("Java", canary), List.of(), null));
        prompt.assemble(hybrid, settings(Duration.ofSeconds(3)));
        var raw = new JobIntelligencePrompt.LlmFirstInput(hybrid.title(), hybrid.description());
        run(raw, Duration.ofSeconds(3));
        var first = JSON.readTree(body.get());
        assertFalse(body.get().contains(canary));
        prompt.assemble(new JobIntelligencePrompt.HybridInput("Other", "Other",
                new JobIntelligencePrompt.CanonicalRequirements(List.of("Rust"), List.of("Go"), new java.math.BigDecimal("99"))),
                settings(Duration.ofSeconds(3)));
        run(raw, Duration.ofSeconds(3));
        assertEquals(first, JSON.readTree(body.get()));
        var failed = assertInstanceOf(JobIntelligenceResult.Failed.class, run(hybrid, Duration.ofSeconds(3)));
        assertEquals(JobIntelligenceResult.Stage.VALIDATION, failed.failure().stage());
        assertEquals(JobIntelligenceResult.Code.EVIDENCE_QUOTE_MISMATCH, failed.failure().code());
        assertEquals(prompt.assemble(hybrid, settings(Duration.ofSeconds(3))).strategyData(), JSON.readTree(body.get()).get("input").asText());
        assertEquals(1, occurrences(body.get(), canary));
        assertEquals(3, requests.get());
    }

    @Test void outerDeadlineAndLateProviderReplyNeverChangeTerminalResult() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        start(exchange -> {
            entered.countDown();
            try { release.await(3, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            try { respond(exchange, 200, completed(validCandidate(), true)); }
            catch (IOException disconnected) { exchange.close(); }
        });
        try {
            var failed = assertInstanceOf(JobIntelligenceResult.Failed.class,
                    run(new JobIntelligencePrompt.LlmFirstInput("T", "D"), Duration.ofMillis(300)));
            assertEquals(JobIntelligenceResult.Code.TIMEOUT, failed.failure().code());
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            release.countDown();
            assertEquals(JobIntelligenceResult.Code.TIMEOUT, failed.failure().code());
            assertEquals(1, requests.get());
        } finally { release.countDown(); }
    }

    @ParameterizedTest @ValueSource(strings = {"server_error", "rate_limit_exceeded", "invalid_prompt", "data_residency_mismatch"})
    void failedEnvelopeRespectsPermanentVersusTransientError(String code) throws Exception {
        String response = envelope("failed", "[]", "\"usage\":null,\"error\":{\"code\":\"" + code
                + "\",\"message\":\"PRIVATE_PROVIDER_ERROR\"}");
        start(exchange -> respond(exchange, 200, response));
        var result = analyze(new JobIntelligencePrompt.LlmFirstInput("T", "D"), Duration.ofSeconds(3));
        assertEquals(code.equals("server_error") || code.equals("rate_limit_exceeded")
                ? JobIntelligenceModel.Outcome.RETRYABLE_FAILURE : JobIntelligenceModel.Outcome.PERMANENT_FAILURE, result.outcome());
        assertNull(result.candidateJson());
        assertFalse(result.toString().contains("PRIVATE_PROVIDER_ERROR"));
        assertEquals(1, requests.get());
    }

    private JobIntelligenceResult run(JobIntelligencePrompt.StrategyInput input, Duration timeout) {
        return new JobIntelligenceRunner(new JobIntelligencePrompt(), new OpenAiJobIntelligenceModel(client))
                .run(new JobIntelligenceRunner.Request(input, settings(timeout)));
    }

    private static String validCandidate() throws IOException {
        try (var stream = OpenAiJobIntelligenceModelTest.class.getResourceAsStream("/job-intelligence/fixtures/valid-llm-first.json")) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private JobIntelligenceModel.ModelAttemptResult analyze(JobIntelligencePrompt.StrategyInput input, Duration timeout) {
        var assembled = new JobIntelligencePrompt().assemble(input, settings(timeout));
        return new OpenAiJobIntelligenceModel(client).analyze(assembled);
    }

    private static JobIntelligencePrompt.ExecutionSettings settings(Duration timeout) {
        return new JobIntelligencePrompt.ExecutionSettings("test-model",
                new JobIntelligenceModel.GenerationSettings(0.2, 600), timeout);
    }

    private void start(Handler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/responses", exchange -> {
            requests.incrementAndGet();
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            method.set(exchange.getRequestMethod());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            handler.handle(exchange);
        });
        server.start();
        client = new OpenAiJobIntelligenceConfiguration().openAiClient(new OpenAiJobIntelligenceProperties(
                true, "test-secret-key", "http://127.0.0.1:" + server.getAddress().getPort(), Duration.ofSeconds(1)));
    }

    private static void respond(HttpExchange exchange, int status, String response) throws IOException {
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.getResponseHeaders().add("x-request-id", "req_loopback_1");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static String completed(String candidate, boolean includeOutput) {
        String output = includeOutput
                ? "[{\"id\":\"msg_1\",\"type\":\"message\",\"status\":\"completed\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":" + quote(candidate) + ",\"annotations\":[]}]}]"
                : "[]";
        return envelope("completed", output, "\"usage\":{\"input_tokens\":11,\"input_tokens_details\":{\"cached_tokens\":3,\"cache_write_tokens\":0},\"output_tokens\":7,\"output_tokens_details\":{\"reasoning_tokens\":0},\"total_tokens\":18}");
    }

    private static String refusal() {
        return envelope("completed", "[{\"id\":\"msg_1\",\"type\":\"message\",\"status\":\"completed\",\"role\":\"assistant\",\"content\":[{\"type\":\"refusal\",\"refusal\":\"no\"}]}]", "\"usage\":null");
    }

    private static String incomplete() {
        return envelope("incomplete", "[{\"id\":\"msg_1\",\"type\":\"message\",\"status\":\"incomplete\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"partial\",\"annotations\":[]}]}]", "\"usage\":null");
    }

    private static String envelope(String status, String output, String usage) {
        return "{\"id\":\"resp_1\",\"object\":\"response\",\"created_at\":0,\"model\":\"returned-model\",\"status\":\"" + status
                + "\",\"output\":" + output + ",\"parallel_tool_calls\":false,\"tool_choice\":\"none\",\"tools\":[]," + usage + "}";
    }

    private static String error(String sentinel, int status) {
        return "{\"error\":{\"message\":\"" + sentinel + "\",\"type\":\"test_error\",\"code\":\"http_" + status + "\"}}";
    }

    private static String quote(String value) { return JSON.writeValueAsString(value); }
    private static int occurrences(String text, String needle) { return (text.length() - text.replace(needle, "").length()) / needle.length(); }
    @FunctionalInterface private interface Handler { void handle(HttpExchange exchange) throws IOException; }
}

