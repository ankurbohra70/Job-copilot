package com.jobcopilot.discovery.workday;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class HttpWorkdayPostingGatewayTest {
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicReference<String> method = new AtomicReference<>();
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicReference<String> language = new AtomicReference<>();
    private HttpServer server;
    private OkHttpClient client;
    private final WorkdaySource source = new WorkdaySource(
            "company.wd5.myworkdayjobs.com", "company", "External_Site", "en-US");

    @AfterEach void close() {
        if (server != null) server.stop(0);
        if (client != null) { client.dispatcher().executorService().shutdownNow(); client.connectionPool().evictAll(); }
    }

    @Test void searchSendsOneExactBoundedPostAndAcceptsObservedOffsetTotalBehavior() throws Exception {
        start(exchange -> respond(exchange, 200, "application/json", searchJson(0)));
        WorkdayFetchResult result = gateway(10_000, 10_000).searchPage(source, new WorkdayPageRequest(2, 2));
        assertThat(result).isInstanceOf(WorkdayFetchResult.SearchSuccess.class);
        assertThat(((WorkdayFetchResult.SearchSuccess) result).reportedTotal()).isZero();
        assertThat(requests).hasValue(1); assertThat(method).hasValue("POST");
        assertThat(path).hasValue("/wday/cxs/company/External_Site/jobs");
        assertThat(body).hasValue("{\"appliedFacets\":{},\"limit\":2,\"offset\":2,\"searchText\":\"\"}");
        assertThat(language).hasValue("en-US");
    }

    @Test void detailSendsOneGetAndReturnsSeparateIdentityCandidates() throws Exception {
        start(exchange -> respond(exchange, 200, "application/problem+json", detailJson()));
        WorkdayFetchResult result = gateway(10_000, 20_000).fetchDetail(source,
                new WorkdayDetailRequest("/job/Remote/Engineer_JR1"));
        assertThat(result).isInstanceOf(WorkdayFetchResult.DetailSuccess.class);
        WorkdayJobDetail detail = ((WorkdayFetchResult.DetailSuccess) result).detail();
        assertThat(detail.opaqueId()).isEqualTo("opaque-id");
        assertThat(detail.jobReqId()).isEqualTo("JR1");
        assertThat(requests).hasValue(1); assertThat(method).hasValue("GET");
        assertThat(path).hasValue("/wday/cxs/company/External_Site/job/Remote/Engineer_JR1");
    }

    @Test void failuresNeverRetryFollowRedirectOrExposeBodies() throws Exception {
        for (Case item : new Case[]{new Case(302, WorkdayFetchResult.FailureKind.REQUEST_REJECTED),
                new Case(404, WorkdayFetchResult.FailureKind.SOURCE_NOT_FOUND),
                new Case(408, WorkdayFetchResult.FailureKind.TIMEOUT),
                new Case(429, WorkdayFetchResult.FailureKind.RATE_LIMITED),
                new Case(500, WorkdayFetchResult.FailureKind.PROVIDER_UNAVAILABLE),
                new Case(503, WorkdayFetchResult.FailureKind.PROVIDER_UNAVAILABLE)}) {
            restart(exchange -> {
                exchange.getResponseHeaders().add("Location", baseUrl() + "/follow");
                exchange.getResponseHeaders().add("Retry-After", "0");
                respond(exchange, item.status(), "text/html", "PRIVATE_PROVIDER_CANARY");
            });
            WorkdayFetchResult result = gateway(1000, 1000).searchPage(source, new WorkdayPageRequest(0, 1));
            assertFailure(result, item.kind(), item.status());
            assertThat(result.toString()).doesNotContain("PRIVATE_PROVIDER_CANARY");
            assertThat(requests).hasValue(1);
        }
    }

    @Test void logsNeverContainProviderBodyOrDetailPath() throws Exception {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory
                .getLogger(HttpWorkdayPostingGateway.class);
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        captured.start();
        logger.addAppender(captured);
        try {
            start(exchange -> respond(exchange, 500, "text/html", "PRIVATE_PROVIDER_CANARY"));
            WorkdayFetchResult result = gateway(1000, 1000).fetchDetail(source,
                    new WorkdayDetailRequest("/job/Private-Path-CANARY"));
            assertFailure(result, WorkdayFetchResult.FailureKind.PROVIDER_UNAVAILABLE, 500);
            assertThat(requests).hasValue(1);
            assertThat(captured.list).isNotEmpty();
            assertThat(captured.list.stream().map(ILoggingEvent::getFormattedMessage).toList())
                    .allSatisfy(message -> assertThat(message)
                            .doesNotContain("PRIVATE_PROVIDER_CANARY", "Private-Path-CANARY"));
        } finally {
            logger.detachAppender(captured);
            captured.stop();
        }
    }

    @Test void htmlMissingContentTypeMalformedAndInvalidJsonAreDistinctAndOneShot() throws Exception {
        start(exchange -> respond(exchange, 200, "text/html", "<html>challenge</html>"));
        assertFailure(gateway(1000, 1000).searchPage(source, new WorkdayPageRequest(0, 1)),
                WorkdayFetchResult.FailureKind.UNEXPECTED_CONTENT_TYPE, 200);
        restart(exchange -> respond(exchange, 200, null, searchJson(1)));
        assertFailure(gateway(1000, 1000).searchPage(source, new WorkdayPageRequest(0, 1)),
                WorkdayFetchResult.FailureKind.UNEXPECTED_CONTENT_TYPE, 200);
        restart(exchange -> respond(exchange, 200, "application/json", "{"));
        assertFailure(gateway(1000, 1000).searchPage(source, new WorkdayPageRequest(0, 1)),
                WorkdayFetchResult.FailureKind.MALFORMED_RESPONSE, 200);
        restart(exchange -> respond(exchange, 200, "application/json", "{}"));
        assertFailure(gateway(1000, 1000).searchPage(source, new WorkdayPageRequest(0, 1)),
                WorkdayFetchResult.FailureKind.INVALID_RESPONSE, 200);
        assertThat(requests).hasValue(1);
    }

    @Test void responseBoundsTimeoutDisconnectAndPreInterruptionAreDeterministic() throws Exception {
        start(exchange -> respond(exchange, 200, "application/json", searchJson(1) + " ".repeat(500)));
        assertFailure(gateway(100, 1000).searchPage(source, new WorkdayPageRequest(0, 1)),
                WorkdayFetchResult.FailureKind.RESPONSE_TOO_LARGE, 200);
        restart(exchange -> {
            try { Thread.sleep(400); respond(exchange, 200, "application/json", searchJson(1)); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); exchange.close(); }
            catch (IOException disconnected) { exchange.close(); }
        });
        client = WorkdayClientConfiguration.transport(Duration.ofMillis(100), Duration.ofMillis(100));
        assertFailure(gatewayWithCurrentClient(1000, 1000).searchPage(source, new WorkdayPageRequest(0, 1)),
                WorkdayFetchResult.FailureKind.TIMEOUT, null);
        restart(HttpExchange::close);
        assertFailure(gateway(1000, 1000).searchPage(source, new WorkdayPageRequest(0, 1)),
                WorkdayFetchResult.FailureKind.CONNECTION_FAILURE, null);
        restart(exchange -> respond(exchange, 200, "application/json", searchJson(1)));
        Thread.currentThread().interrupt();
        try {
            assertFailure(gateway(1000, 1000).searchPage(source, new WorkdayPageRequest(0, 1)),
                    WorkdayFetchResult.FailureKind.INTERRUPTED, null);
            assertThat(requests).hasValue(0);
        } finally { Thread.interrupted(); }
    }

    @Test void compressedResponseIsBoundedAfterDecompressionAndEmptyJsonIsMalformed() throws Exception {
        start(exchange -> {
            byte[] compressed = gzip(searchJson(1) + " ".repeat(500));
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.getResponseHeaders().add("Content-Encoding", "gzip");
            exchange.sendResponseHeaders(200, compressed.length);
            exchange.getResponseBody().write(compressed);
            exchange.close();
        });
        assertFailure(gateway(150, 1000).searchPage(source, new WorkdayPageRequest(0, 1)),
                WorkdayFetchResult.FailureKind.RESPONSE_TOO_LARGE, 200);
        assertThat(requests).hasValue(1);
        restart(exchange -> respond(exchange, 200, "application/json", ""));
        assertFailure(gateway(1000, 1000).searchPage(source, new WorkdayPageRequest(0, 1)),
                WorkdayFetchResult.FailureKind.INVALID_RESPONSE, 200);
        assertThat(requests).hasValue(1);
    }

    @Test void inFlightInterruptionCancelsOneAttemptAndRestoresThreadFlag() throws Exception {
        CountDownLatch received = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        start(exchange -> {
            received.countDown();
            try { release.await(3, TimeUnit.SECONDS); respond(exchange, 200, "application/json", searchJson(1)); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); exchange.close(); }
            catch (IOException cancelled) { exchange.close(); }
        });
        HttpWorkdayPostingGateway gateway = gateway(1000, 1000);
        AtomicReference<WorkdayFetchResult> outcome = new AtomicReference<>();
        AtomicBoolean flag = new AtomicBoolean();
        Thread caller = new Thread(() -> {
            outcome.set(gateway.searchPage(source, new WorkdayPageRequest(0, 1)));
            flag.set(Thread.currentThread().isInterrupted());
        });
        try {
            caller.start();
            assertThat(received.await(2, TimeUnit.SECONDS)).isTrue();
            caller.interrupt();
            caller.join(2_000);
            assertThat(caller.isAlive()).isFalse();
            assertFailure(outcome.get(), WorkdayFetchResult.FailureKind.INTERRUPTED, null);
            assertThat(flag).isTrue();
            assertThat(requests).hasValue(1);
        } finally { release.countDown(); caller.join(2_000); }
    }

    private static byte[] gzip(String value) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(output)) {
            gzip.write(value.getBytes(StandardCharsets.UTF_8));
        }
        return output.toByteArray();
    }

    private HttpWorkdayPostingGateway gateway(long searchLimit, long detailLimit) {
        client = WorkdayClientConfiguration.transport(Duration.ofSeconds(2), Duration.ofSeconds(2));
        return gatewayWithCurrentClient(searchLimit, detailLimit);
    }
    private HttpWorkdayPostingGateway gatewayWithCurrentClient(long searchLimit, long detailLimit) {
        return new HttpWorkdayPostingGateway(client,
                new WorkdayEndpointResolver(URI.create(baseUrl() + "/wday/cxs/")),
                new WorkdayResponseDecoder(), searchLimit, detailLimit);
    }
    private void start(Handler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet(); method.set(exchange.getRequestMethod()); path.set(exchange.getRequestURI().getRawPath());
            language.set(exchange.getRequestHeaders().getFirst("Accept-Language"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)); handler.handle(exchange);
        }); server.start();
    }
    private void restart(Handler handler) throws IOException {
        if (server != null) server.stop(0);
        if (client != null) { client.dispatcher().executorService().shutdownNow(); client.connectionPool().evictAll(); client = null; }
        requests.set(0); method.set(null); path.set(null); body.set(null); start(handler);
    }
    private String baseUrl() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
    private static void respond(HttpExchange exchange, int status, String type, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (type != null) exchange.getResponseHeaders().add("Content-Type", type);
        exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
    }
    private static void assertFailure(WorkdayFetchResult result, WorkdayFetchResult.FailureKind kind, Integer status) {
        assertThat(result).isInstanceOf(WorkdayFetchResult.Failure.class);
        assertThat(((WorkdayFetchResult.Failure) result).kind()).isEqualTo(kind);
        assertThat(((WorkdayFetchResult.Failure) result).httpStatus()).isEqualTo(status);
    }
    private static String searchJson(int total) {
        return "{\"total\":" + total + ",\"jobPostings\":[{\"title\":\"Engineer\","
                + "\"externalPath\":\"/job/Remote/Engineer_JR1\",\"bulletFields\":[\"JR1\"]}]}";
    }
    private String detailJson() {
        return "{\"jobPostingInfo\":{\"id\":\"opaque-id\",\"title\":\"Engineer\","
                + "\"jobDescription\":\"<p>Build</p>\",\"jobReqId\":\"JR1\","
                + "\"jobPostingSiteId\":\"External_Site\",\"externalUrl\":\"https://"
                + source.endpointHost() + "/External_Site/job/Remote/Engineer_JR1\","
                + "\"posted\":true,\"canApply\":true}}";
    }
    private record Case(int status, WorkdayFetchResult.FailureKind kind) {}
    @FunctionalInterface interface Handler { void handle(HttpExchange exchange) throws IOException; }
}
