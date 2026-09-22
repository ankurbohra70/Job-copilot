package com.jobcopilot.discovery.workday;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class HttpWorkdayPostingGateway implements WorkdayPostingGateway {
    private static final Logger log = LoggerFactory.getLogger(HttpWorkdayPostingGateway.class);
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final int BUFFER_SIZE = 8192;
    private final OkHttpClient client;
    private final WorkdayEndpointResolver endpoints;
    private final WorkdayResponseDecoder decoder;
    private final long maxSearchBytes;
    private final long maxDetailBytes;

    HttpWorkdayPostingGateway(OkHttpClient client, WorkdayEndpointResolver endpoints,
            WorkdayResponseDecoder decoder, long maxSearchBytes, long maxDetailBytes) {
        this.client = Objects.requireNonNull(client); this.endpoints = Objects.requireNonNull(endpoints);
        this.decoder = Objects.requireNonNull(decoder);
        if (maxSearchBytes < 1 || maxSearchBytes > Integer.MAX_VALUE
                || maxDetailBytes < 1 || maxDetailBytes > Integer.MAX_VALUE)
            throw new IllegalArgumentException("response byte limit is invalid");
        this.maxSearchBytes = maxSearchBytes; this.maxDetailBytes = maxDetailBytes;
    }

    @Override public WorkdayFetchResult searchPage(WorkdaySource source, WorkdayPageRequest page) {
        Objects.requireNonNull(source); Objects.requireNonNull(page);
        String json = "{\"appliedFacets\":{},\"limit\":" + page.limit() + ",\"offset\":"
                + page.offset() + ",\"searchText\":\"\"}";
        Request request = new Request.Builder().url(endpoints.search(source).toASCIIString())
                .header("Accept", "application/json").header("Accept-Language", source.locale())
                .post(RequestBody.create(json, JSON)).build();
        return execute(source, WorkdayFetchResult.Operation.SEARCH, page, null, request, maxSearchBytes);
    }

    @Override public WorkdayFetchResult fetchDetail(WorkdaySource source, WorkdayDetailRequest detail) {
        Objects.requireNonNull(source); Objects.requireNonNull(detail);
        Request request = new Request.Builder().url(endpoints.detail(source, detail).toASCIIString())
                .header("Accept", "application/json").header("Accept-Language", source.locale()).get().build();
        return execute(source, WorkdayFetchResult.Operation.DETAIL, null, detail, request, maxDetailBytes);
    }

    private WorkdayFetchResult execute(WorkdaySource source, WorkdayFetchResult.Operation operation,
            WorkdayPageRequest page, WorkdayDetailRequest detail, Request request, long byteLimit) {
        long started = System.nanoTime();
        if (Thread.currentThread().isInterrupted())
            return failure(source, operation, page, detail, WorkdayFetchResult.FailureKind.INTERRUPTED, null, started);
        Call call = client.newCall(request);
        CompletableFuture<WorkdayFetchResult> completion = new CompletableFuture<>();
        call.enqueue(new Callback() {
            @Override public void onFailure(Call ignored, IOException failure) {
                completion.complete(transportFailure(source, operation, page, detail, failure, started));
            }
            @Override public void onResponse(Call ignored, Response response) {
                WorkdayFetchResult result;
                try (response) { result = handle(source, operation, page, detail, response, byteLimit, started); }
                catch (IOException failure) { result = transportFailure(source, operation, page, detail, failure, started); }
                completion.complete(result);
            }
        });
        WorkdayFetchResult result;
        try { result = completion.get(); }
        catch (InterruptedException interrupted) {
            call.cancel();
            result = failure(source, operation, page, detail,
                    WorkdayFetchResult.FailureKind.INTERRUPTED, null, started);
            completion.complete(result); Thread.currentThread().interrupt();
        } catch (java.util.concurrent.ExecutionException impossible) {
            result = failure(source, operation, page, detail,
                    WorkdayFetchResult.FailureKind.CONNECTION_FAILURE, null, started);
        }
        log(source, operation, page, result);
        return result;
    }

    private WorkdayFetchResult handle(WorkdaySource source, WorkdayFetchResult.Operation operation,
            WorkdayPageRequest page, WorkdayDetailRequest detail, Response response, long byteLimit, long started)
            throws IOException {
        int status = response.code();
        if (status != 200) return failure(source, operation, page, detail, classify(status), status, started);
        ResponseBody responseBody = response.body();
        if (responseBody == null)
            return failure(source, operation, page, detail, WorkdayFetchResult.FailureKind.INVALID_RESPONSE,
                    status, started);
        if (!jsonContentType(responseBody.contentType()))
            return failure(source, operation, page, detail,
                    WorkdayFetchResult.FailureKind.UNEXPECTED_CONTENT_TYPE, status, started);
        BoundedBody body;
        try { body = readBounded(responseBody.byteStream(), byteLimit); }
        catch (ResponseTooLarge oversized) {
            return failure(source, operation, page, detail,
                    WorkdayFetchResult.FailureKind.RESPONSE_TOO_LARGE, status, started);
        }
        if (operation == WorkdayFetchResult.Operation.SEARCH) {
            var decoded = decoder.decodeSearch(body.openStream(), page);
            if (decoded instanceof WorkdayResponseDecoder.SearchDecodeResult.Decoded success)
                return new WorkdayFetchResult.SearchSuccess(source, page, success.reportedTotal(),
                        success.postings(), success.returnedRowCount(), elapsed(started));
            return failure(source, operation, page, detail,
                    decoded instanceof WorkdayResponseDecoder.SearchDecodeResult.Malformed
                            ? WorkdayFetchResult.FailureKind.MALFORMED_RESPONSE
                            : WorkdayFetchResult.FailureKind.INVALID_RESPONSE, status, started);
        }
        var decoded = decoder.decodeDetail(body.openStream(), source);
        if (decoded instanceof WorkdayResponseDecoder.DetailDecodeResult.Decoded success)
            return new WorkdayFetchResult.DetailSuccess(source, detail, success.detail(), elapsed(started));
        return failure(source, operation, page, detail,
                decoded instanceof WorkdayResponseDecoder.DetailDecodeResult.Malformed
                        ? WorkdayFetchResult.FailureKind.MALFORMED_RESPONSE
                        : WorkdayFetchResult.FailureKind.INVALID_RESPONSE, status, started);
    }

    private static boolean jsonContentType(MediaType type) {
        if (type == null) return false;
        return type.type().equalsIgnoreCase("application")
                && (type.subtype().equalsIgnoreCase("json")
                    || type.subtype().toLowerCase(Locale.ROOT).endsWith("+json"));
    }

    private WorkdayFetchResult transportFailure(WorkdaySource source, WorkdayFetchResult.Operation operation,
            WorkdayPageRequest page, WorkdayDetailRequest detail, IOException failure, long started) {
        WorkdayFetchResult.FailureKind kind = failure instanceof SocketTimeoutException
                || failure instanceof InterruptedIOException
                ? WorkdayFetchResult.FailureKind.TIMEOUT : WorkdayFetchResult.FailureKind.CONNECTION_FAILURE;
        return failure(source, operation, page, detail, kind, null, started);
    }

    static WorkdayFetchResult.FailureKind classify(int status) {
        if (status == 404) return WorkdayFetchResult.FailureKind.SOURCE_NOT_FOUND;
        if (status == 429) return WorkdayFetchResult.FailureKind.RATE_LIMITED;
        if (status == 408) return WorkdayFetchResult.FailureKind.TIMEOUT;
        if (status >= 500 && status <= 599) return WorkdayFetchResult.FailureKind.PROVIDER_UNAVAILABLE;
        return WorkdayFetchResult.FailureKind.REQUEST_REJECTED;
    }

    private WorkdayFetchResult.Failure failure(WorkdaySource source, WorkdayFetchResult.Operation operation,
            WorkdayPageRequest page, WorkdayDetailRequest detail, WorkdayFetchResult.FailureKind kind,
            Integer status, long started) {
        return new WorkdayFetchResult.Failure(source, operation, page, detail, kind, status, elapsed(started));
    }

    private static BoundedBody readBounded(InputStream input, long maxBytes) throws IOException, ResponseTooLarge {
        List<BodyChunk> chunks = new ArrayList<>(); long consumed = 0;
        while (true) {
            int capacity = (int) Math.min(BUFFER_SIZE, maxBytes - consumed + 1);
            byte[] bytes = new byte[capacity]; int size = 0;
            while (size < capacity) {
                int read = input.read(bytes, size, capacity - size);
                if (read == -1) {
                    if (size > 0) chunks.add(new BodyChunk(bytes, size));
                    return new BoundedBody(List.copyOf(chunks));
                }
                if (read == 0) {
                    int one = input.read();
                    if (one == -1) {
                        if (size > 0) chunks.add(new BodyChunk(bytes, size));
                        return new BoundedBody(List.copyOf(chunks));
                    }
                    bytes[size++] = (byte) one;
                } else size += read;
            }
            if (consumed + size > maxBytes) throw new ResponseTooLarge();
            chunks.add(new BodyChunk(bytes, size)); consumed += size;
        }
    }

    private static void log(WorkdaySource source, WorkdayFetchResult.Operation operation,
            WorkdayPageRequest page, WorkdayFetchResult result) {
        if (result instanceof WorkdayFetchResult.SearchSuccess success) {
            log.debug("Workday fetch provider=WORKDAY tenant={} site={} operation=SEARCH offset={} limit={} "
                            + "outcome=SUCCESS latencyMs={} postingCount={}", source.tenantKey(), source.siteKey(),
                    page.offset(), page.limit(), success.latency().toMillis(), success.postings().size());
        } else if (result instanceof WorkdayFetchResult.DetailSuccess success) {
            log.debug("Workday fetch provider=WORKDAY tenant={} site={} operation=DETAIL outcome=SUCCESS latencyMs={}",
                    source.tenantKey(), source.siteKey(), success.latency().toMillis());
        } else {
            WorkdayFetchResult.Failure failure = (WorkdayFetchResult.Failure) result;
            log.warn("Workday fetch provider=WORKDAY tenant={} site={} operation={} outcome={} httpStatus={} latencyMs={}",
                    source.tenantKey(), source.siteKey(), operation, failure.kind(), failure.httpStatus(),
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
