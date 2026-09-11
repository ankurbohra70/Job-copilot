package com.jobcopilot.intelligence.gemini;

import com.jobcopilot.intelligence.JobIntelligenceModel;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** One non-streaming Gemini generateContent request; domain decoding and validation stay in the runner. */
public final class GeminiJobIntelligenceModel implements JobIntelligenceModel {
    private static final Set<String> REFUSAL_REASONS = Set.of(
            "SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT", "IMAGE_SAFETY", "SPII");
    private final JsonMapper json = JsonMapper.builder().build();
    private final HttpClient client;
    private final URI baseUrl;
    private final String apiKey;

    public GeminiJobIntelligenceModel(HttpClient client, URI baseUrl, String apiKey) {
        this.client = Objects.requireNonNull(client);
        this.baseUrl = Objects.requireNonNull(baseUrl);
        if (apiKey == null || apiKey.isBlank()) throw new IllegalArgumentException("Gemini API key is required");
        this.apiKey = apiKey;
    }

    @Override public String providerId() { return "gemini"; }

    @Override public ModelAttemptResult analyze(ModelInput input) {
        Objects.requireNonNull(input);
        long start = System.nanoTime();
        byte[] body = requestBody(input).getBytes(StandardCharsets.UTF_8);
        AtomicBoolean subscribed = new AtomicBoolean();
        HttpRequest request = HttpRequest.newBuilder(endpoint(input.model()))
                .timeout(input.remainingTimeout())
                .header("Content-Type", "application/json")
                .header("x-goog-api-key", apiKey)
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> subscribed.compareAndSet(false, true)
                        ? new ByteArrayInputStream(body) : null))
                .build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300)
                return httpFailure(response.statusCode(), elapsed(start));
            return map(response.body(), elapsed(start));
        } catch (HttpTimeoutException timeout) {
            return failure(Outcome.RETRYABLE_FAILURE, "transport_timeout", elapsed(start));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return failure(Outcome.RETRYABLE_FAILURE, "transport_interrupted", elapsed(start));
        } catch (IOException transport) {
            return failure(Outcome.RETRYABLE_FAILURE, "transport_io", elapsed(start));
        }
    }

    private String requestBody(ModelInput input) {
        try { json.readTree(input.outputSchema()); }
        catch (RuntimeException invalid) {
            throw new IllegalStateException("The bundled job-intelligence schema is invalid", invalid);
        }
        var root = json.createObjectNode();
        var systemParts = root.putObject("systemInstruction").putArray("parts");
        systemParts.addObject().put("text", input.instructions());
        // Gemini rejects this complete schema as a provider-side responseJsonSchema constraint. Supplying the
        // same trusted schema as instructions preserves the opaque contract; the strict local decoder remains authority.
        systemParts.addObject().put("text", "Output JSON must conform to this schema:\n" + input.outputSchema());
        root.putArray("contents").addObject().put("role", "user").putArray("parts").addObject()
                .put("text", input.strategyData());
        var generation = root.putObject("generationConfig");
        generation.put("candidateCount", 1);
        generation.put("maxOutputTokens", input.generationSettings().maxOutputTokens());
        if (input.generationSettings().temperature() != null)
            generation.put("temperature", input.generationSettings().temperature());
        generation.put("responseMimeType", "application/json");
        return json.writeValueAsString(root);
    }

    private URI endpoint(String model) {
        String root = baseUrl.toASCIIString().replaceFirst("/+$", "");
        String encoded = URLEncoder.encode(model, StandardCharsets.UTF_8).replace("+", "%20");
        return URI.create(root + "/v1beta/models/" + encoded + ":generateContent");
    }

    private ModelAttemptResult map(String body, Duration latency) {
        try {
            JsonNode root = json.readTree(body);
            if (root == null || !root.isObject()) return failure(Outcome.PERMANENT_FAILURE, "malformed_response", latency);
            String returnedModel = text(root, "modelVersion");
            String responseId = text(root, "responseId");
            Usage usage = usage(root.get("usageMetadata"));
            JsonNode candidates = root.get("candidates");
            if (candidates == null || !candidates.isArray() || candidates.isEmpty()) {
                return new ModelAttemptResult(null,
                        root.path("promptFeedback").hasNonNull("blockReason") ? Outcome.REFUSED : Outcome.PERMANENT_FAILURE,
                        root.path("promptFeedback").hasNonNull("blockReason") ? "prompt_blocked" : "missing_candidate",
                        returnedModel, responseId, usage, latency);
            }
            if (candidates.size() != 1)
                return new ModelAttemptResult(null, Outcome.PERMANENT_FAILURE, "multiple_output_candidates",
                        returnedModel, responseId, usage, latency);
            JsonNode candidate = candidates.get(0);
            String finishReason = text(candidate, "finishReason");
            if ("MAX_TOKENS".equals(finishReason))
                return new ModelAttemptResult(null, Outcome.INCOMPLETE, "max_output_tokens", returnedModel,
                        responseId, usage, latency);
            if (finishReason != null && !"STOP".equals(finishReason))
                return new ModelAttemptResult(null, REFUSAL_REASONS.contains(finishReason) ? Outcome.REFUSED
                        : Outcome.PERMANENT_FAILURE, REFUSAL_REASONS.contains(finishReason)
                        ? "response_refused" : "unexpected_finish_reason", returnedModel, responseId, usage, latency);
            JsonNode parts = candidate.path("content").path("parts");
            if (!parts.isArray()) return new ModelAttemptResult(null, Outcome.PERMANENT_FAILURE,
                    "missing_output_text", returnedModel, responseId, usage, latency);
            var texts = new ArrayList<String>();
            for (JsonNode part : parts) if (part.hasNonNull("text")) texts.add(part.get("text").asText());
            if (texts.size() != 1) return new ModelAttemptResult(null, Outcome.PERMANENT_FAILURE,
                    texts.isEmpty() ? "missing_output_text" : "multiple_output_parts",
                    returnedModel, responseId, usage, latency);
            return new ModelAttemptResult(texts.getFirst(), Outcome.COMPLETED, null,
                    returnedModel, responseId, usage, latency);
        } catch (RuntimeException malformed) {
            return failure(Outcome.PERMANENT_FAILURE, "malformed_response", latency);
        }
    }

    private static ModelAttemptResult httpFailure(int status, Duration latency) {
        boolean retryable = status == 408 || status == 409 || status == 429 || status >= 500;
        return failure(retryable ? Outcome.RETRYABLE_FAILURE : Outcome.PERMANENT_FAILURE,
                retryable ? "transient_http" : "request_rejected", latency);
    }

    private static Usage usage(JsonNode node) {
        if (node == null || !node.isObject()) return null;
        return new Usage(number(node, "promptTokenCount"), number(node, "candidatesTokenCount"),
                number(node, "cachedContentTokenCount"));
    }

    private static Long number(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.canConvertToLong() ? value.longValue() : null;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    private static ModelAttemptResult failure(Outcome outcome, String code, Duration latency) {
        return new ModelAttemptResult(null, outcome, code, null, null, null, latency);
    }

    private static Duration elapsed(long start) {
        return Duration.ofNanos(Math.max(0, System.nanoTime() - start));
    }
}
