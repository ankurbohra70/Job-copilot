package com.jobcopilot.discovery.lever;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class HttpLeverPostingGateway implements LeverPostingGateway {
    private static final Logger log = LoggerFactory.getLogger(HttpLeverPostingGateway.class);
    private static final int BUFFER_SIZE = 8192;

    private final OkHttpClient client;
    private final LeverEndpointResolver endpoints;
    private final LeverResponseDecoder decoder;
    private final long maxResponseBytes;

    HttpLeverPostingGateway(OkHttpClient client, LeverEndpointResolver endpoints,
            LeverResponseDecoder decoder, long maxResponseBytes) {
        this.client = Objects.requireNonNull(client);
        this.endpoints = Objects.requireNonNull(endpoints);
        this.decoder = Objects.requireNonNull(decoder);
        if (maxResponseBytes <= 0 || maxResponseBytes > Integer.MAX_VALUE)
            throw new IllegalArgumentException("maxResponseBytes is invalid");
        this.maxResponseBytes = maxResponseBytes;
    }

    @Override
    public LeverFetchResult fetchPage(LeverSource source, LeverPageRequest page) {
        Objects.requireNonNull(source);
        Objects.requireNonNull(page);
        long started = System.nanoTime();
        if (Thread.currentThread().isInterrupted())
            return failure(source, page, LeverFetchResult.FailureKind.INTERRUPTED, null, started);

        Request request = new Request.Builder()
                .url(endpoints.resolve(source, page).toASCIIString())
                .header("Accept", "application/json")
                .get()
                .build();
        Call call = client.newCall(request);
        CompletableFuture<LeverFetchResult> completion = new CompletableFuture<>();
        call.enqueue(new Callback() {
            @Override
            public void onFailure(Call ignored, IOException failure) {
                completion.complete(transportFailure(source, page, failure, started));
            }

            @Override
            public void onResponse(Call ignored, Response response) {
                LeverFetchResult result;
                try (response) {
                    result = handleResponse(source, page, response, started);
                } catch (IOException failure) {
                    result = transportFailure(source, page, failure, started);
                }
                completion.complete(result);
            }
        });

        LeverFetchResult result;
        try {
            result = completion.get();
        } catch (InterruptedException interrupted) {
            call.cancel();
            result = failure(source, page, LeverFetchResult.FailureKind.INTERRUPTED, null, started);
            completion.complete(result);
            Thread.currentThread().interrupt();
        } catch (java.util.concurrent.ExecutionException impossible) {
            result = failure(source, page, LeverFetchResult.FailureKind.CONNECTION_FAILURE, null, started);
        }
        log(result);
        return result;
    }

    private LeverFetchResult handleResponse(LeverSource source, LeverPageRequest page, Response response, long started)
            throws IOException {
            int status = response.code();
            if (status != 200) return failure(source, page, classify(status), status, started);
            ResponseBody responseBody = response.body();
            if (responseBody == null)
                return failure(source, page, LeverFetchResult.FailureKind.INVALID_RESPONSE, status, started);
            BoundedBody body;
            try {
                body = readBounded(responseBody.byteStream());
            } catch (ResponseTooLarge oversized) {
                return failure(source, page, LeverFetchResult.FailureKind.RESPONSE_TOO_LARGE, status, started);
            }
            LeverResponseDecoder.DecodeResult decoded = decoder.decode(body.openStream());
            if (decoded instanceof LeverResponseDecoder.DecodeResult.Decoded success) {
                Duration latency = elapsed(started);
                return new LeverFetchResult.Success(source, page, success.postings(), latency);
            }
            return failure(source, page, decoded instanceof LeverResponseDecoder.DecodeResult.Malformed
                    ? LeverFetchResult.FailureKind.MALFORMED_RESPONSE
                    : LeverFetchResult.FailureKind.INVALID_RESPONSE, status, started);
    }

    private LeverFetchResult transportFailure(LeverSource source, LeverPageRequest page,
            IOException transport, long started) {
        if (transport instanceof SocketTimeoutException) {
            return failure(source, page, LeverFetchResult.FailureKind.TIMEOUT, null, started);
        }
        if (transport instanceof InterruptedIOException) {
            return failure(source, page, LeverFetchResult.FailureKind.TIMEOUT, null, started);
        }
        return failure(source, page, LeverFetchResult.FailureKind.CONNECTION_FAILURE, null, started);
    }

    private BoundedBody readBounded(InputStream input) throws IOException, ResponseTooLarge {
        List<BodyChunk> chunks = new ArrayList<>();
        long consumed = 0;
        while (true) {
            int capacity = (int) Math.min(BUFFER_SIZE, maxResponseBytes - consumed + 1);
            byte[] bytes = new byte[capacity];
            int size = 0;
            while (size < capacity) {
                int read = input.read(bytes, size, capacity - size);
                if (read == -1) {
                    if (size > 0) chunks.add(new BodyChunk(bytes, size));
                    return new BoundedBody(List.copyOf(chunks));
                }
                if (read == 0) {
                    int single = input.read();
                    if (single == -1) {
                        if (size > 0) chunks.add(new BodyChunk(bytes, size));
                        return new BoundedBody(List.copyOf(chunks));
                    }
                    bytes[size++] = (byte) single;
                } else {
                    size += read;
                }
            }
            if (consumed + size > maxResponseBytes) throw new ResponseTooLarge();
            chunks.add(new BodyChunk(bytes, size));
            consumed += size;
        }
    }

    static LeverFetchResult.FailureKind classify(int status) {
        if (status == 404) return LeverFetchResult.FailureKind.SOURCE_NOT_FOUND;
        if (status == 429) return LeverFetchResult.FailureKind.RATE_LIMITED;
        if (status == 408) return LeverFetchResult.FailureKind.TIMEOUT;
        if (status >= 500 && status <= 599) return LeverFetchResult.FailureKind.PROVIDER_UNAVAILABLE;
        return LeverFetchResult.FailureKind.REQUEST_REJECTED;
    }

    private LeverFetchResult.Failure failure(LeverSource source, LeverPageRequest page,
            LeverFetchResult.FailureKind kind, Integer status, long started) {
        Duration latency = elapsed(started);
        return new LeverFetchResult.Failure(source, page, kind, status, latency);
    }

    private static void log(LeverFetchResult result) {
        if (result instanceof LeverFetchResult.Success success) {
            log.debug("Lever fetch provider=LEVER sourceKey={} region={} skip={} limit={} outcome=SUCCESS "
                            + "httpStatus=200 latencyMs={} postingCount={}", success.source().sourceKey(),
                    success.source().region(), success.page().skip(), success.page().limit(),
                    success.latency().toMillis(), success.postings().size());
        } else {
            LeverFetchResult.Failure failure = (LeverFetchResult.Failure) result;
            log.warn("Lever fetch provider=LEVER sourceKey={} region={} skip={} limit={} outcome={} "
                            + "httpStatus={} latencyMs={}", failure.source().sourceKey(), failure.source().region(),
                    failure.page().skip(), failure.page().limit(), failure.kind(), failure.httpStatus(),
                    failure.latency().toMillis());
        }
    }

    private static Duration elapsed(long started) {
        return Duration.ofNanos(Math.max(0, System.nanoTime() - started));
    }

    private static final class ResponseTooLarge extends Exception {}

    private record BodyChunk(byte[] bytes, int size) {}

    private record BoundedBody(List<BodyChunk> chunks) {
        InputStream openStream() {
            List<InputStream> streams = new ArrayList<>(chunks.size());
            for (BodyChunk chunk : chunks) streams.add(new ByteArrayInputStream(chunk.bytes(), 0, chunk.size()));
            return new java.io.SequenceInputStream(java.util.Collections.enumeration(streams));
        }
    }
}
