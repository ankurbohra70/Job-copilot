package com.jobcopilot.intelligence.gemini;

import com.jobcopilot.intelligence.JobIntelligenceModel;
import com.jobcopilot.intelligence.JobIntelligencePrompt;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;

class GeminiJobIntelligenceModelTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicReference<String> key = new AtomicReference<>();
    private HttpServer server;

    @AfterEach void close() { if (server != null) server.stop(0); }

    @Test void requestPreservesOpaqueAssemblyIsolationAndUsesJsonModeWithTheAuthoritativeSchema() throws Exception {
        start(exchange -> respond(exchange, 200, completed("{}")));
        var prompt = new JobIntelligencePrompt();
        var settings = settings(Duration.ofSeconds(3));
        var raw = new JobIntelligencePrompt.LlmFirstInput("Backend", "Java is required.");
        var expected = prompt.assemble(raw, settings);
        var result = model().analyze(expected);

        assertThat(result.outcome()).isEqualTo(JobIntelligenceModel.Outcome.COMPLETED);
        assertThat(requests).hasValue(1);
        assertThat(key).hasValue("test-key");
        JsonNode sent = JSON.readTree(body.get());
        assertThat(sent.at("/systemInstruction/parts/0/text").asText()).isEqualTo(expected.instructions());
        assertThat(sent.at("/systemInstruction/parts/1/text").asText()).contains(expected.outputSchema());
        assertThat(sent.at("/contents/0/parts/0/text").asText()).isEqualTo(expected.strategyData());
        assertThat(sent.at("/generationConfig/candidateCount").asInt()).isEqualTo(1);
        assertThat(sent.at("/generationConfig/responseMimeType").asText()).isEqualTo("application/json");
        assertThat(sent.at("/generationConfig/responseJsonSchema").isMissingNode()).isTrue();
        assertThat(body.get()).doesNotContain("promptVersion", "schemaVersion");

        requests.set(0);
        String canary = "CANONICAL_CANARY_621";
        var hybrid = prompt.assemble(new JobIntelligencePrompt.HybridInput("Backend", "Java is required.",
                new JobIntelligencePrompt.CanonicalRequirements(List.of(canary), List.of(), null)), settings);
        model().analyze(hybrid);
        assertThat(body.get()).contains(canary);
        assertThat(occurrences(body.get(), canary)).isEqualTo(1);
        assertThat(requests).hasValue(1);
    }

    @Test void completedEnvelopeMapsCandidateMetadataAndUsage() throws Exception {
        start(exchange -> respond(exchange, 200, completed("{\"facts\":{}}")));
        var result = model().analyze(assembled(Duration.ofSeconds(3)));
        assertThat(result.candidateJson()).isEqualTo("{\"facts\":{}}");
        assertThat(result.returnedModel()).isEqualTo("gemini-test-version");
        assertThat(result.providerRequestId()).isEqualTo("response-test-1");
        assertThat(result.usage()).isEqualTo(new JobIntelligenceModel.Usage(13L, 7L, 2L));
        assertThat(requests).hasValue(1);
    }

    @ParameterizedTest @ValueSource(ints = {408, 409, 429, 500, 503})
    void transientHttpFailuresAreSanitizedAndNeverRetried(int status) throws Exception {
        start(exchange -> respond(exchange, status, "{\"error\":{\"message\":\"PRIVATE_BODY\"}}"));
        var result = model().analyze(assembled(Duration.ofSeconds(2)));
        assertThat(result.outcome()).isEqualTo(JobIntelligenceModel.Outcome.RETRYABLE_FAILURE);
        assertThat(result.failureCode()).isEqualTo("transient_http");
        assertThat(result.toString()).doesNotContain("PRIVATE_BODY", "test-key");
        assertThat(requests).hasValue(1);
    }

    @ParameterizedTest @ValueSource(ints = {301, 302, 307, 308, 400, 401, 403, 404})
    void redirectAuthenticationAndPermanentFailuresAreSanitizedAndNeverRetried(int status) throws Exception {
        start(exchange -> {
            exchange.getResponseHeaders().add("Location", baseUrl() + "/replayed");
            exchange.getResponseHeaders().add("WWW-Authenticate", "Basic realm=private");
            respond(exchange, status, "{\"error\":{\"message\":\"PRIVATE_BODY\"}}" );
        });
        var result = model().analyze(assembled(Duration.ofSeconds(2)));
        assertThat(result.outcome()).isEqualTo(JobIntelligenceModel.Outcome.PERMANENT_FAILURE);
        assertThat(result.failureCode()).isEqualTo("request_rejected");
        assertThat(result.toString()).doesNotContain("PRIVATE_BODY", "test-key");
        assertThat(requests).hasValue(1);
    }

    @Test void malformedBlockedIncompleteAndMultiplePartsMapToTypedFailures() throws Exception {
        start(exchange -> respond(exchange, 200, "not-json"));
        assertThat(model().analyze(assembled(Duration.ofSeconds(2))).failureCode()).isEqualTo("malformed_response");
        restart(exchange -> respond(exchange, 200, "{\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}"));
        assertThat(model().analyze(assembled(Duration.ofSeconds(2))).outcome()).isEqualTo(JobIntelligenceModel.Outcome.REFUSED);
        restart(exchange -> respond(exchange, 200, envelope("MAX_TOKENS", "[{\"text\":\"partial\"}]")));
        assertThat(model().analyze(assembled(Duration.ofSeconds(2))).outcome()).isEqualTo(JobIntelligenceModel.Outcome.INCOMPLETE);
        restart(exchange -> respond(exchange, 200, envelope("STOP", "[{\"text\":\"{}\"},{\"text\":\"{}\"}]")));
        assertThat(model().analyze(assembled(Duration.ofSeconds(2))).failureCode()).isEqualTo("multiple_output_parts");
        assertThat(requests).hasValue(1);
    }

    @Test void timeoutIsTypedAndDoesNotRetry() throws Exception {
        start(exchange -> {
            try { Thread.sleep(400); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            try { respond(exchange, 200, completed("{}")); } catch (IOException disconnected) { exchange.close(); }
        });
        var result = model().analyze(assembled(Duration.ofMillis(100)));
        assertThat(result.outcome()).isEqualTo(JobIntelligenceModel.Outcome.RETRYABLE_FAILURE);
        assertThat(result.failureCode()).isEqualTo("transport_timeout");
        assertThat(requests).hasValue(1);
    }

    private JobIntelligenceModel.ModelInput assembled(Duration timeout) {
        return new JobIntelligencePrompt().assemble(new JobIntelligencePrompt.LlmFirstInput("T", "D"), settings(timeout));
    }

    private static JobIntelligencePrompt.ExecutionSettings settings(Duration timeout) {
        return new JobIntelligencePrompt.ExecutionSettings("gemini-test",
                new JobIntelligenceModel.GenerationSettings(0.0, 800), timeout);
    }

    private GeminiJobIntelligenceModel model() {
        return new GeminiJobIntelligenceModel(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),
                URI.create(baseUrl()), "test-key");
    }

    private String baseUrl() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

    private void start(Handler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            key.set(exchange.getRequestHeaders().getFirst("x-goog-api-key"));
            handler.handle(exchange);
        });
        server.start();
    }

    private void restart(Handler handler) throws IOException {
        server.stop(0); requests.set(0); start(handler);
    }

    private static void respond(HttpExchange exchange, int status, String response) throws IOException {
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static String completed(String candidate) {
        return envelope("STOP", "[{\"text\":" + JSON.writeValueAsString(candidate) + ",\"thought\":false}]");
    }

    private static String envelope(String finishReason, String parts) {
        return "{\"candidates\":[{\"content\":{\"parts\":" + parts + "},\"finishReason\":\"" + finishReason
                + "\"}],\"usageMetadata\":{\"promptTokenCount\":13,\"candidatesTokenCount\":7,"
                + "\"cachedContentTokenCount\":2},\"modelVersion\":\"gemini-test-version\","
                + "\"responseId\":\"response-test-1\"}";
    }

    private static int occurrences(String text, String value) {
        return (text.length() - text.replace(value, "").length()) / value.length();
    }

    @FunctionalInterface interface Handler { void handle(HttpExchange exchange) throws IOException; }
}
