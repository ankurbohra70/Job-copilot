package com.jobcopilot.intelligence.openai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.jobcopilot.intelligence.JobIntelligenceModel;
import com.openai.client.OpenAIClient;
import com.openai.core.JsonValue;
import com.openai.core.RequestOptions;
import com.openai.core.http.HttpResponseFor;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIInvalidDataException;
import com.openai.errors.OpenAIServiceException;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseError;
import com.openai.models.responses.ResponseFormatTextJsonSchemaConfig;
import com.openai.models.responses.ResponseOutputMessage;
import com.openai.models.responses.ResponseStatus;
import com.openai.models.responses.ResponseTextConfig;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;

/** One non-streaming OpenAI Responses request; decoding and domain validation remain outside this adapter. */
public final class OpenAiJobIntelligenceModel implements JobIntelligenceModel {
    private static final JsonMapper SDK_JSON = JsonMapper.builder().build();
    private static final TypeReference<Map<String, JsonValue>> SCHEMA_MAP = new TypeReference<>() {};
    private final OpenAIClient client;

    public OpenAiJobIntelligenceModel(OpenAIClient client) {
        this.client = Objects.requireNonNull(client);
    }

    @Override public String providerId() { return "openai"; }

    @Override public ModelAttemptResult analyze(ModelInput input) {
        Objects.requireNonNull(input);
        long start = System.nanoTime();
        ResponseCreateParams params = request(input);
        RequestOptions options = RequestOptions.builder().timeout(input.remainingTimeout()).build();
        try (HttpResponseFor<Response> raw = client.responses().withRawResponse().create(params, options)) {
            String requestId = raw.requestId().orElse(null);
            Response response = raw.parse();
            return map(response, requestId, elapsed(start));
        } catch (OpenAIIoException transport) {
            return failure(Outcome.RETRYABLE_FAILURE, "transport_io", elapsed(start));
        } catch (OpenAIInvalidDataException malformed) {
            return failure(Outcome.PERMANENT_FAILURE, "malformed_response", elapsed(start));
        } catch (OpenAIServiceException service) {
            boolean retryable = service.statusCode() == 408 || service.statusCode() == 409
                    || service.statusCode() == 429 || service.statusCode() >= 500;
            return failure(retryable ? Outcome.RETRYABLE_FAILURE : Outcome.PERMANENT_FAILURE,
                    retryable ? "transient_http" : "request_rejected", elapsed(start));
        }
    }

    private static ResponseCreateParams request(ModelInput input) {
        Map<String, JsonValue> schema;
        try {
            schema = SDK_JSON.readValue(input.outputSchema(), SCHEMA_MAP);
        } catch (java.io.IOException invalidTrustedSchema) {
            throw new IllegalStateException("The bundled job-intelligence schema is invalid", invalidTrustedSchema);
        }
        var format = ResponseFormatTextJsonSchemaConfig.builder()
                .name("job_intelligence_output")
                .strict(true)
                .schema(ResponseFormatTextJsonSchemaConfig.Schema.builder().additionalProperties(schema).build())
                .build();
        var builder = ResponseCreateParams.builder()
                .model(input.model())
                .instructions(input.instructions())
                .input(input.strategyData())
                .maxOutputTokens(input.generationSettings().maxOutputTokens())
                .store(false)
                .background(false)
                .text(ResponseTextConfig.builder().format(format).build());
        if (input.generationSettings().temperature() != null) {
            builder.temperature(input.generationSettings().temperature());
        }
        return builder.build();
    }

    private static ModelAttemptResult map(Response response, String requestId, Duration latency) {
        String returnedModel = response._model().asKnown().map(value -> value.asString()).orElse(null);
        Usage usage = response.usage().map(value -> new Usage(value._inputTokens().asKnown().orElse(null),
                value._outputTokens().asKnown().orElse(null), value._inputTokensDetails().asKnown()
                        .flatMap(details -> details._cachedTokens().asKnown()).orElse(null))).orElse(null);
        ResponseStatus status = response.status().orElse(null);
        if (ResponseStatus.INCOMPLETE.equals(status)) {
            return new ModelAttemptResult(null, Outcome.INCOMPLETE, "response_incomplete", returnedModel, requestId, usage, latency);
        }
        if (ResponseStatus.FAILED.equals(status)) {
            var code = response.error().map(error -> error.code()).orElse(null);
            boolean retryable = ResponseError.Code.SERVER_ERROR.equals(code)
                    || ResponseError.Code.RATE_LIMIT_EXCEEDED.equals(code)
                    || ResponseError.Code.VECTOR_STORE_TIMEOUT.equals(code);
            return new ModelAttemptResult(null, retryable ? Outcome.RETRYABLE_FAILURE : Outcome.PERMANENT_FAILURE,
                    "response_failed", returnedModel, requestId, usage, latency);
        }
        if (!ResponseStatus.COMPLETED.equals(status)) {
            return new ModelAttemptResult(null, Outcome.PERMANENT_FAILURE, "unexpected_response_status", returnedModel, requestId, usage, latency);
        }
        boolean refusal = false;
        var candidates = new ArrayList<String>();
        for (var item : response.output()) {
            if (!item.isMessage()) continue;
            for (ResponseOutputMessage.Content content : item.asMessage().content()) {
                if (content.isRefusal()) refusal = true;
                else if (content.isOutputText()) candidates.add(content.asOutputText().text());
            }
        }
        if (refusal) {
            return new ModelAttemptResult(null, Outcome.REFUSED, "response_refused", returnedModel, requestId, usage, latency);
        }
        if (candidates.size() > 1) {
            return new ModelAttemptResult(null, Outcome.PERMANENT_FAILURE, "multiple_output_candidates", returnedModel, requestId, usage, latency);
        }
        return new ModelAttemptResult(candidates.isEmpty() ? null : candidates.getFirst(), Outcome.COMPLETED, null,
                returnedModel, requestId, usage, latency);
    }

    private static ModelAttemptResult failure(Outcome outcome, String code, Duration latency) {
        return new ModelAttemptResult(null, outcome, code, null, null, null, latency);
    }

    private static Duration elapsed(long start) {
        return Duration.ofNanos(Math.max(0, System.nanoTime() - start));
    }
}
