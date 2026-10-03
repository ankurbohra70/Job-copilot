package com.jobcopilot.discovery.lever;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpLeverPostingGatewayTest {
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicReference<String> method = new AtomicReference<>();
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> query = new AtomicReference<>();
    private final AtomicReference<String> accept = new AtomicReference<>();
    private HttpServer server;
    private OkHttpClient client;

    @AfterEach void close() {
        if (server != null) server.stop(0);
        if (client != null) {
            client.dispatcher().executorService().shutdownNow();
            client.connectionPool().evictAll();
        }
    }

    @Test void sendsExactlyOneCorrectPageRequestAndReturnsImmutableSuccess() throws Exception {
        start(exchange -> respond(exchange, 200, "application/json", "[" + LeverResponseDecoderTest.minimal("one") + "]"));
        LeverFetchResult result = gateway(Duration.ofSeconds(2), 10_000).fetchPage(
                new LeverSource(LeverRegion.GLOBAL, "site /?#"), new LeverPageRequest(12, 1));

        assertThat(result).isInstanceOf(LeverFetchResult.Success.class);
        var success = (LeverFetchResult.Success) result;
        assertThat(success.postings()).extracting(LeverPosting::externalId).containsExactly("one");
        assertThat(success.latency()).isGreaterThanOrEqualTo(Duration.ZERO);
        assertThat(requests).hasValue(1);
        assertThat(method).hasValue("GET");
        assertThat(path).hasValue("/v0/postings/site%20%2F%3F%23");
        assertThat(query).hasValue("mode=json&skip=12&limit=1");
        assertThat(accept).hasValue("application/json");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "a.b|/v0/postings/a.b", "a..b|/v0/postings/a..b",
            "-example|/v0/postings/-example", "example-|/v0/postings/example-",
            "a_b|/v0/postings/a_b", "a/b|/v0/postings/a%2Fb",
            "a?b|/v0/postings/a%3Fb", "a#b|/v0/postings/a%23b",
            "a%b|/v0/postings/a%25b", "a&b|/v0/postings/a%26b",
            "a=b|/v0/postings/a%3Db", "café|/v0/postings/caf%C3%A9"
    })
    void phaseOnePunctuationAndUnicodeStayInOneActualPathSegment(String sourceKey, String expectedPath)
            throws Exception {
        start(exchange -> respond(exchange, 200, "application/json", "[]"));

        assertSuccess(gateway(Duration.ofSeconds(2), 1000).fetchPage(
                new LeverSource(LeverRegion.GLOBAL, sourceKey), page()), 0);
        assertThat(path).hasValue(expectedPath);
        assertThat(query).hasValue("mode=json&skip=0&limit=10");
        assertThat(requests).hasValue(1);
    }

    @Test void dotSegmentsFailBeforeAnyRequest() throws Exception {
        start(exchange -> respond(exchange, 200, "application/json", "[]"));

        assertThatThrownBy(() -> new LeverSource(LeverRegion.GLOBAL, "."))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LeverSource(LeverRegion.GLOBAL, ".."))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(requests).hasValue(0);
    }

    @Test void emptyAndValidJsonWithMissingOrUnusualContentTypeSucceedOnce() throws Exception {
        start(exchange -> respond(exchange, 200, null, "[]"));
        assertSuccess(gateway(Duration.ofSeconds(2), 1000).fetchPage(source(), page()), 0);
        assertThat(requests).hasValue(1);

        restart(exchange -> respond(exchange, 200, "text/plain; charset=utf-8",
                "[" + LeverResponseDecoderTest.minimal("one") + "]"));
        assertSuccess(gateway(Duration.ofSeconds(2), 1000).fetchPage(source(), page()), 1);
        assertThat(requests).hasValue(1);
    }

    @Test void malformedAndInvalidResponsesAreAtomicAndNeverRetried() throws Exception {
        start(exchange -> respond(exchange, 200, "application/json", "[broken"));
        assertFailure(gateway(Duration.ofSeconds(2), 1000).fetchPage(source(), page()),
                LeverFetchResult.FailureKind.MALFORMED_RESPONSE, 200);
        assertThat(requests).hasValue(1);

        restart(exchange -> respond(exchange, 200, "application/json", "{}"));
        assertFailure(gateway(Duration.ofSeconds(2), 1000).fetchPage(source(), page()),
                LeverFetchResult.FailureKind.INVALID_RESPONSE, 200);
        assertThat(requests).hasValue(1);

        restart(exchange -> respond(exchange, 200, "application/json",
                "[" + LeverResponseDecoderTest.minimal("good") + ",{}]"));
        assertFailure(gateway(Duration.ofSeconds(2), 1000).fetchPage(source(), page()),
                LeverFetchResult.FailureKind.INVALID_RESPONSE, 200);
        assertThat(requests).hasValue(1);
    }

    @Test void redirectAndHttpFailuresNeverFollowRetryOrExposeBody() throws Exception {
        start(exchange -> {
            exchange.getResponseHeaders().add("Location", baseUrl() + "/v0/postings/redirected");
            respond(exchange, 302, "text/plain", "PRIVATE_PROVIDER_CANARY");
        });
        assertOneFailure(LeverFetchResult.FailureKind.REQUEST_REJECTED, 302);

        restart(exchange -> respond(exchange, 404, "text/plain", "PRIVATE_PROVIDER_CANARY"));
        assertOneFailure(LeverFetchResult.FailureKind.SOURCE_NOT_FOUND, 404);

        restart(exchange -> respond(exchange, 429, "text/plain", "PRIVATE_PROVIDER_CANARY"));
        assertOneFailure(LeverFetchResult.FailureKind.RATE_LIMITED, 429);

        restart(exchange -> respond(exchange, 500, "text/plain", "PRIVATE_PROVIDER_CANARY"));
        assertOneFailure(LeverFetchResult.FailureKind.PROVIDER_UNAVAILABLE, 500);

        restart(exchange -> {
            exchange.getResponseHeaders().add("Retry-After", "0");
            respond(exchange, 503, "text/plain", "PRIVATE_PROVIDER_CANARY");
        });
        assertOneFailure(LeverFetchResult.FailureKind.PROVIDER_UNAVAILABLE, 503);
    }

    @ParameterizedTest
    @MethodSource("retryAfterValues")
    void every503RetryAfterVariantIsRemovedBeforeOkHttpFollowUp(String retryAfter) throws Exception {
        start(exchange -> {
            if (!"<none>".equals(retryAfter)) exchange.getResponseHeaders().add("Retry-After", retryAfter);
            respond(exchange, 503, "text/plain", "PRIVATE_PROVIDER_CANARY");
        });

        assertOneFailure(LeverFetchResult.FailureKind.PROVIDER_UNAVAILABLE, 503);
    }

    static Stream<String> retryAfterValues() {
        return Stream.of("<none>", "0", "00", "000", "1", "120",
                "Wed, 21 Oct 2015 07:28:00 GMT", "999999999999999999999999");
    }

    @ParameterizedTest
    @CsvSource({
            "408,TIMEOUT", "429,RATE_LIMITED", "500,PROVIDER_UNAVAILABLE",
            "502,PROVIDER_UNAVAILABLE", "504,PROVIDER_UNAVAILABLE",
            "301,REQUEST_REJECTED", "302,REQUEST_REJECTED",
            "307,REQUEST_REJECTED", "308,REQUEST_REJECTED"
    })
    void representativeStatusesNeverFollowOrRetry(int status, LeverFetchResult.FailureKind kind) throws Exception {
        start(exchange -> {
            exchange.getResponseHeaders().add("Retry-After", "0");
            exchange.getResponseHeaders().add("Location", baseUrl() + "/follow-up");
            respond(exchange, status, "text/plain", "PRIVATE_PROVIDER_CANARY");
        });

        assertOneFailure(kind, status);
    }

    @Test void timeoutStopsAfterOneRequest() throws Exception {
        start(exchange -> {
            try { Thread.sleep(500); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            try { respond(exchange, 200, "application/json", "[]"); } catch (IOException disconnected) { exchange.close(); }
        });
        LeverFetchResult result = gateway(Duration.ofMillis(100), 1000).fetchPage(source(), page());
        assertFailure(result, LeverFetchResult.FailureKind.TIMEOUT, null);
        assertThat(requests).hasValue(1);
    }

    @Test void connectionRefusalIsClassifiedWithoutApplicationRetry() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        client = client(Duration.ofMillis(300));
        URI unavailable = URI.create("http://127.0.0.1:" + port + "/v0/postings/");
        var gateway = new HttpLeverPostingGateway(client, new LeverEndpointResolver(unavailable, unavailable),
                new LeverResponseDecoder(), 1000);
        assertFailure(gateway.fetchPage(source(), page()), LeverFetchResult.FailureKind.CONNECTION_FAILURE, null);
    }

    @Test void streamingBoundStopsConsumptionBeforeChunkedBodyCompletes() throws Exception {
        AtomicInteger chunksWritten = new AtomicInteger();
        int totalChunks = 200;
        start(exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, 0);
            byte[] chunk = "x".repeat(1024).getBytes(StandardCharsets.UTF_8);
            try {
                for (int i = 0; i < totalChunks; i++) {
                    exchange.getResponseBody().write(chunk);
                    exchange.getResponseBody().flush();
                    chunksWritten.incrementAndGet();
                    try { Thread.sleep(5); } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt(); break;
                    }
                }
            } catch (IOException disconnectedAfterLimit) {
                // Expected: the bounded reader closes the response before the producer finishes.
            } finally {
                exchange.close();
            }
        });

        LeverFetchResult result = gateway(Duration.ofSeconds(3), 1500).fetchPage(source(), page());
        assertFailure(result, LeverFetchResult.FailureKind.RESPONSE_TOO_LARGE, 200);
        assertThat(requests).hasValue(1);
        assertThat(chunksWritten.get()).isLessThan(totalChunks);
    }

    @Test void alreadyInterruptedThreadDoesNotCreateARequestAndPreservesStatus() throws Exception {
        start(exchange -> respond(exchange, 200, "application/json", "[]"));
        Thread.currentThread().interrupt();
        try {
            assertFailure(gateway(Duration.ofSeconds(1), 1000).fetchPage(source(), page()),
                    LeverFetchResult.FailureKind.INTERRUPTED, null);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(requests).hasValue(0);
        } finally {
            Thread.interrupted();
        }
    }

    @Test void inFlightInterruptionCancelsCallReturnsEarlyAndPreservesStatus() throws Exception {
        CountDownLatch received = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        start(exchange -> {
            received.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
                respond(exchange, 200, "application/json", "[]");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                exchange.close();
            } catch (IOException cancelled) {
                exchange.close();
            }
        });
        AtomicReference<LeverFetchResult> result = new AtomicReference<>();
        AtomicBoolean interrupted = new AtomicBoolean();
        Thread worker = new Thread(() -> {
            result.set(gateway(Duration.ofSeconds(3), 1000).fetchPage(source(), page()));
            interrupted.set(Thread.currentThread().isInterrupted());
        });

        try {
            worker.start();
            assertThat(received.await(1, TimeUnit.SECONDS)).isTrue();
            worker.interrupt();
            worker.join(1000);

            assertThat(worker.isAlive()).isFalse();
            assertFailure(result.get(), LeverFetchResult.FailureKind.INTERRUPTED, null);
            assertThat(interrupted).isTrue();
            assertThat(requests).hasValue(1);
        } finally {
            release.countDown();
            worker.join(1000);
        }
    }

    @Test void exactBodyLimitSucceedsAndLimitPlusOneFails() throws Exception {
        int limit = 128;
        String exact = "[]" + " ".repeat(limit - 2);
        start(exchange -> respond(exchange, 200, "application/json", exact));
        assertSuccess(gateway(Duration.ofSeconds(2), limit).fetchPage(source(), page()), 0);
        assertThat(requests).hasValue(1);

        restart(exchange -> respond(exchange, 200, "application/json", exact + " "));
        assertFailure(gateway(Duration.ofSeconds(2), limit).fetchPage(source(), page()),
                LeverFetchResult.FailureKind.RESPONSE_TOO_LARGE, 200);
        assertThat(requests).hasValue(1);
    }

    private void assertOneFailure(LeverFetchResult.FailureKind kind, int status) {
        LeverFetchResult result = gateway(Duration.ofSeconds(2), 1000).fetchPage(source(), page());
        assertFailure(result, kind, status);
        assertThat(result.toString()).doesNotContain("PRIVATE_PROVIDER_CANARY");
        assertThat(requests).hasValue(1);
    }

    private HttpLeverPostingGateway gateway(Duration timeout, long maxBytes) {
        client = client(timeout);
        URI base = URI.create(baseUrl() + "/v0/postings/");
        return new HttpLeverPostingGateway(client, new LeverEndpointResolver(base, base),
                new LeverResponseDecoder(), maxBytes);
    }

    private static OkHttpClient client(Duration timeout) {
        return LeverClientConfiguration.transport(timeout, timeout);
    }

    private static LeverSource source() { return new LeverSource(LeverRegion.GLOBAL, "test-site"); }
    private static LeverPageRequest page() { return new LeverPageRequest(0, 10); }

    private static void assertSuccess(LeverFetchResult result, int count) {
        assertThat(result).isInstanceOf(LeverFetchResult.Success.class);
        assertThat(((LeverFetchResult.Success) result).postings()).hasSize(count);
    }

    private static void assertFailure(LeverFetchResult result, LeverFetchResult.FailureKind kind, Integer status) {
        assertThat(result).isInstanceOf(LeverFetchResult.Failure.class);
        LeverFetchResult.Failure failure = (LeverFetchResult.Failure) result;
        assertThat(failure.kind()).isEqualTo(kind);
        assertThat(failure.httpStatus()).isEqualTo(status);
        assertThat(failure.latency()).isGreaterThanOrEqualTo(Duration.ZERO);
    }

    private String baseUrl() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

    private void start(Handler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            method.set(exchange.getRequestMethod());
            path.set(exchange.getRequestURI().getRawPath());
            query.set(exchange.getRequestURI().getRawQuery());
            accept.set(exchange.getRequestHeaders().getFirst("Accept"));
            handler.handle(exchange);
        });
        server.start();
    }

    private void restart(Handler handler) throws IOException {
        server.stop(0);
        if (client != null) {
            client.dispatcher().executorService().shutdownNow();
            client.connectionPool().evictAll();
            client = null;
        }
        requests.set(0);
        start(handler);
    }

    private static void respond(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        if (contentType != null) exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @FunctionalInterface interface Handler { void handle(HttpExchange exchange) throws IOException; }
}
