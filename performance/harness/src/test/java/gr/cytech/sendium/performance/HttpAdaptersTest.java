package gr.cytech.sendium.performance;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static gr.cytech.sendium.performance.MessageLedger.Admission.*;
import static gr.cytech.sendium.performance.MessageLedger.Event.*;

class HttpAdaptersTest {
    private RunConfig config(boolean dlr, int timeout, int requests) {
        return new RunConfig(1, "http", 100, 100, 0, 1, 1, dlr, 0, 0, timeout, requests, 10);
    }

    private MessageLedger ledger(RunConfig config) {
        return new MessageLedger(config, "http-test", System::nanoTime, TimeUnit.SECONDS.toNanos(5));
    }

    private HttpIngressSettings settings(URI api, URI callback) {
        return new HttpIngressSettings(api, "user +&", "password? &=", "from &", "123456", callback);
    }

    @Test
    void encodesApiParametersAndCorrelatesCallbackBeforeAcknowledgement() throws Exception {
        RunConfig config = config(true, 3000, 10);
        MessageLedger ledger = ledger(config);
        String gatewayId = UUID.randomUUID().toString();
        AtomicReference<Throwable> handlerFailure = new AtomicReference<>();
        try (HttpReceiptReceiver receiver = new HttpReceiptReceiver(new InetSocketAddress("127.0.0.1", 0),
                "/dlr", 2, 10, true, ledger)) {
            receiver.start();
            URI callback = URI.create("http://127.0.0.1:" + receiver.port() + "/dlr");
            try (Api api = new Api(exchange -> {
                try {
                    var query = HttpReceiptReceiver.decodeQuery(exchange.getRequestURI().getRawQuery());
                    assertEquals("GET", exchange.getRequestMethod());
                    assertEquals("user +&", query.get("username"));
                    assertEquals("password? &=", query.get("password"));
                    assertEquals("from &", query.get("from"));
                    assertEquals("0", query.get("coding"));
                    assertEquals("UTF-8", query.get("charset"));
                    String token = query.get("text");
                    providerEvents(ledger, token, true);
                    String callbackUrl = query.get("dlr-url").replace("%s", gatewayId).replace("%d", "1");
                    assertEquals(204, get(URI.create(callbackUrl)));
                    assertEquals(1, ledger.snapshot().pendingAdmission());
                    respond(exchange, 202, gatewayId);
                } catch (Throwable failure) {
                    handlerFailure.set(failure);
                    respond(exchange, 500, "test handler failed");
                }
            }); HttpAdmissionClient client = new HttpAdmissionClient(config, settings(api.uri(), callback), ledger)) {
                assertEquals(ACCEPTED, client.submit(true).orElseThrow().completion().get(5, TimeUnit.SECONDS));
                assertNull(handlerFailure.get());
                assertEquals(1, ledger.snapshot().acceptedReceiptForwarded());
                assertEquals(0, ledger.snapshot().violations());
                assertFalse(ledger.hasOutstandingWork());
                assertEquals(0, client.pendingRequests());
            }
        }
    }

    @Test
    void disabledDeliveryReportsOmitCallbackParameter() throws Exception {
        RunConfig config = config(false, 3000, 10);
        MessageLedger ledger = ledger(config);
        AtomicReference<java.util.Map<String, String>> parameters = new AtomicReference<>();
        try (Api api = new Api(exchange -> {
            var query = HttpReceiptReceiver.decodeQuery(exchange.getRequestURI().getRawQuery());
            parameters.set(query);
            providerEvents(ledger, query.get("text"), false);
            respond(exchange, 202, UUID.randomUUID().toString());
        }); HttpAdmissionClient client = new HttpAdmissionClient(config, settings(api.uri(), null), ledger)) {
            assertEquals(ACCEPTED, client.submit(true).orElseThrow().completion().get(5, TimeUnit.SECONDS));
            assertFalse(parameters.get().containsKey("dlr-url"));
            assertEquals(0, ledger.snapshot().receiptsGenerated());
            assertEquals(1, ledger.snapshot().acceptedProviderCompleted());
        }
    }

    @Test
    void overloadIsRejectedWhileAuthenticationAndServerErrorsAreDiagnosed() throws Exception {
        for (int status : new int[]{503, 429, 401, 500, 302}) {
            RunConfig config = config(false, 3000, 10);
            MessageLedger ledger = ledger(config);
            try (Api api = new Api(exchange -> {
                if (status == 302) { exchange.getResponseHeaders().set("Location", "/redirect-target"); }
                respond(exchange, status, "response");
            }); HttpAdmissionClient client = new HttpAdmissionClient(config, settings(api.uri(), null), ledger)) {
                assertEquals(status == 500 || status == 302 ? FAILED : REJECTED,
                        client.submit(true).orElseThrow().completion().get(5, TimeUnit.SECONDS));
                long problemCount = client.problems().values().stream().mapToLong(Long::longValue).sum();
                assertEquals(status == 503 || status == 429 ? 0 : 1, problemCount);
            }
        }
    }

    @Test
    void requestBoundsExposeMissedTargetsAndTimeoutRemainsAmbiguous() throws Exception {
        RunConfig config = config(false, 200, 1);
        MessageLedger ledger = ledger(config);
        CountDownLatch received = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (Api api = new Api(exchange -> {
            received.countDown();
            release.await(3, TimeUnit.SECONDS);
            respond(exchange, 202, UUID.randomUUID().toString());
        }); HttpAdmissionClient client = new HttpAdmissionClient(config, settings(api.uri(), null), ledger)) {
            var submission = client.submit(true).orElseThrow();
            assertTrue(received.await(3, TimeUnit.SECONDS));
            assertTrue(client.submit(true).isEmpty());
            assertEquals(TIMED_OUT, submission.completion().get(5, TimeUnit.SECONDS));
            assertEquals(1, ledger.snapshot().missed());
            providerEvents(ledger, submission.token(), false);
            assertEquals(1, ledger.snapshot().providerObservedWithoutAcceptance());
            assertEquals(0, ledger.snapshot().accepted());
            release.countDown();
        } finally { release.countDown(); }
    }

    @Test
    void timeoutCoversAStalledBodyAfterAcceptedHeaders() throws Exception {
        RunConfig config = config(false, 200, 1);
        MessageLedger ledger = ledger(config);
        CountDownLatch release = new CountDownLatch(1);
        try (Api api = new Api(exchange -> {
            exchange.sendResponseHeaders(202, 100);
            exchange.getResponseBody().write('x'); exchange.getResponseBody().flush();
            release.await(3, TimeUnit.SECONDS); exchange.close();
        }); HttpAdmissionClient client = new HttpAdmissionClient(config, settings(api.uri(), null), ledger)) {
            assertEquals(TIMED_OUT, client.submit(true).orElseThrow().completion().get(2, TimeUnit.SECONDS));
            assertEquals(0, client.pendingRequests());
            assertEquals(1, ledger.snapshot().timedOut());
            assertEquals(0, client.problems().get(HttpAdmissionClient.Problem.TRANSPORT_FAILURE));
        } finally { release.countDown(); }
    }

    @Test
    void closeCancelsPendingRequestsAndPreservesAmbiguousFailedAccounting() throws Exception {
        RunConfig config = config(false, 3000, 1);
        MessageLedger ledger = ledger(config);
        CountDownLatch received = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (Api api = new Api(exchange -> {
            received.countDown();
            release.await(3, TimeUnit.SECONDS);
            respond(exchange, 202, UUID.randomUUID().toString());
        }); HttpAdmissionClient client = new HttpAdmissionClient(config, settings(api.uri(), null), ledger)) {
            var submission = client.submit(true).orElseThrow();
            assertTrue(received.await(3, TimeUnit.SECONDS));
            client.close();
            client.close();
            assertEquals(FAILED, submission.completion().get(3, TimeUnit.SECONDS));
            assertEquals(0, client.pendingRequests());
            assertEquals(0, ledger.snapshot().pendingAdmission());
            assertEquals(1, ledger.snapshot().failed());
            assertTrue(ledger.hasOutstandingWork());
            assertThrows(IllegalStateException.class, () -> client.submit(true));
        } finally { release.countDown(); }
    }

    @Test
    void malformedAndOversizedAcknowledgementsFailWithoutUnboundedBodyStorage() throws Exception {
        for (String body : new String[]{"not-a-uuid", "x".repeat(8192)}) {
            RunConfig config = config(false, 3000, 10);
            MessageLedger ledger = ledger(config);
            try (Api api = new Api(exchange -> respond(exchange, 202, body));
                    HttpAdmissionClient client = new HttpAdmissionClient(config, settings(api.uri(), null), ledger)) {
                assertEquals(FAILED, client.submit(true).orElseThrow().completion().get(5, TimeUnit.SECONDS));
                assertEquals(1, ledger.snapshot().failed());
                assertEquals(1, client.problems().values().stream().mapToLong(Long::longValue).sum());
            }
        }
    }

    @Test
    void callbacksValidateIdentityStatusAndDuplicateFieldsAndAcknowledgeDuplicates() throws Exception {
        RunConfig config = config(true, 3000, 10);
        MessageLedger ledger = ledger(config);
        String token = ledger.attempt(true);
        String id = UUID.randomUUID().toString();
        ledger.admission(token, ACCEPTED, id);
        providerEvents(ledger, token, true);
        try (HttpReceiptReceiver receiver = new HttpReceiptReceiver(new InetSocketAddress("127.0.0.1", 0),
                "/dlr", 2, 10, true, ledger)) {
            receiver.start();
            String url = "http://127.0.0.1:" + receiver.port() + "/dlr?token=" + token + "&messageId=" + id + "&status=1";
            assertEquals(204, get(URI.create(url)));
            assertEquals(204, get(URI.create(url)));
            assertEquals(400, get(URI.create(url.replace(id, UUID.randomUUID().toString()))));
            assertEquals(400, get(URI.create(url.replace(token, "unknown:1"))));
            assertEquals(422, get(URI.create(url.replace("status=1", "status=2"))));
            assertEquals(400, get(URI.create(url + "&status=1")));
            assertEquals(1, ledger.snapshot().receiptsForwarded());
            assertEquals(1, ledger.snapshot().duplicateReceipts());
            assertEquals(3, ledger.snapshot().violations());
            assertEquals(1, receiver.problems().get(HttpReceiptReceiver.Problem.UNEXPECTED_STATUS));
        }
    }

    @Test
    void expiredDuplicateHistoryIsAcknowledgedWithAnExplicitCoverageLimitation() throws Exception {
        RunConfig config = config(true, 3000, 10);
        MessageLedger ledger = new MessageLedger(config, "http-test", System::nanoTime, 0);
        String token = ledger.attempt(true);
        String id = UUID.randomUUID().toString();
        ledger.admission(token, ACCEPTED, id);
        providerEvents(ledger, token, true);
        assertTrue(ledger.receipt(token, id));
        try (HttpReceiptReceiver receiver = new HttpReceiptReceiver(new InetSocketAddress("127.0.0.1", 0),
                "/dlr", 1, 1, true, ledger)) {
            receiver.start();
            URI url = URI.create("http://127.0.0.1:" + receiver.port() + "/dlr?token=" + token + "&messageId=" + id + "&status=1");
            assertEquals(204, get(url));
            assertEquals(1, receiver.problems().get(HttpReceiptReceiver.Problem.LIMITED_HISTORY));
            assertEquals(1, ledger.eventsBeyondRetention());
            assertEquals(0, ledger.snapshot().violations());
            assertEquals(1, ledger.snapshot().receiptsForwarded());
        }
    }

    @Test
    void receiverQueueSaturationIsVisibleAndStillProcessesBoundedCallbacks() throws Exception {
        RunConfig config = config(true, 3000, 10);
        MessageLedger ledger = ledger(config);
        try (HttpReceiptReceiver receiver = new HttpReceiptReceiver(new InetSocketAddress("127.0.0.1", 0),
                "/dlr", 1, 1, true, ledger); HttpClient http = HttpClient.newHttpClient()) {
            receiver.start();
            var urls = new java.util.ArrayList<URI>();
            for (int i = 0; i < 5; i++) {
                String token = ledger.attempt(true);
                String id = UUID.randomUUID().toString();
                ledger.admission(token, ACCEPTED, id);
                providerEvents(ledger, token, true);
                urls.add(URI.create("http://127.0.0.1:" + receiver.port() + "/dlr?token=" + token + "&messageId=" + id + "&status=1"));
            }
            var futures = new java.util.ArrayList<java.util.concurrent.CompletableFuture<HttpResponse<Void>>>();
            synchronized (ledger) {
                for (URI url : urls) {
                    futures.add(http.sendAsync(HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(5)).GET().build(),
                            HttpResponse.BodyHandlers.discarding()));
                }
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (receiver.problems().get(HttpReceiptReceiver.Problem.SATURATION) == 0
                        && System.nanoTime() < deadline) { Thread.sleep(5); }
                assertTrue(receiver.problems().get(HttpReceiptReceiver.Problem.SATURATION) > 0);
            }
            for (var future : futures) { assertEquals(204, future.get(5, TimeUnit.SECONDS).statusCode()); }
            assertEquals(5, ledger.snapshot().acceptedReceiptForwarded());
        }
    }

    @Test
    void gatewayIdentityMismatchAfterEarlyCallbackAndReusedIdsAreViolations() {
        MessageLedger ledger = ledger(config(true, 3000, 10));
        String token = ledger.attempt(true);
        providerEvents(ledger, token, true);
        assertTrue(ledger.receipt(token, "early-id"));
        ledger.admission(token, ACCEPTED, "different-id");
        String other = ledger.attempt(true);
        ledger.admission(other, ACCEPTED, "different-id");
        assertEquals(2, ledger.snapshot().violations());
    }

    private static void providerEvents(MessageLedger ledger, String token, boolean dlr) {
        ledger.event(token, PROVIDER_ARRIVAL);
        ledger.event(token, PROVIDER_RESPONSE);
        if (dlr) { ledger.event(token, RECEIPT_GENERATED); }
    }

    @Test
    void kannelReceiptsUseSimulatorIdentityAndMayArriveBeforeAdmission() throws Exception {
        for (String acknowledgement : new String[]{"0: Accepted for delivery", "3: Queued for later delivery"}) {
            RunConfig config = config(true, 3000, 10);
            MessageLedger ledger = ledger(config);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            try (HttpReceiptReceiver receiver = new HttpReceiptReceiver(new InetSocketAddress("127.0.0.1", 0),
                    "/dlr", 2, 10, true, ledger, HttpGatewayProtocol.KANNEL)) {
                receiver.start();
                URI callback = URI.create("http://127.0.0.1:" + receiver.port() + "/dlr");
                try (Api api = new Api(exchange -> {
                    try {
                        var query = HttpReceiptReceiver.decodeQuery(exchange.getRequestURI().getRawQuery());
                        assertEquals("1", query.get("dlr-mask"));
                        String token = query.get("text");
                        ledger.event(token, PROVIDER_ARRIVAL);
                        assertTrue(ledger.assignProviderId(token, "p-1"));
                        ledger.event(token, PROVIDER_RESPONSE);
                        ledger.event(token, RECEIPT_GENERATED);
                        assertTrue(query.get("dlr-url").contains("messageId=%F&status=%d"));
                        URI receipt = URI.create(query.get("dlr-url").replace("%F", "p-1").replace("%d", "1"));
                        assertEquals(200, get(receipt));
                        assertEquals(1, ledger.snapshot().pendingAdmission());
                        respond(exchange, 202, acknowledgement);
                    } catch (Throwable error) {
                        failure.set(error);
                        respond(exchange, 500, "test handler failed");
                    }
                }); HttpAdmissionClient client = new HttpAdmissionClient(config, settings(api.uri(), callback),
                        ledger, HttpGatewayProtocol.KANNEL)) {
                    assertEquals(ACCEPTED, client.submit(true).orElseThrow().completion().get(5, TimeUnit.SECONDS));
                    assertNull(failure.get());
                    assertEquals(1, ledger.snapshot().acceptedReceiptForwarded());
                    assertEquals(0, ledger.snapshot().violations());
                    assertFalse(ledger.hasOutstandingWork());
                }
            }
        }
    }

    @Test
    void kannelRejectsUuidAndPartialAcceptanceBodiesAndOmitsDlrSettingsWhenDisabled() throws Exception {
        for (String body : new String[]{UUID.randomUUID().toString(), "0: Accepted", "0: Accepted for delivery extra"}) {
            RunConfig config = config(false, 3000, 10);
            MessageLedger ledger = ledger(config);
            AtomicReference<java.util.Map<String, String>> query = new AtomicReference<>();
            try (Api api = new Api(exchange -> {
                query.set(HttpReceiptReceiver.decodeQuery(exchange.getRequestURI().getRawQuery()));
                respond(exchange, 202, body);
            }); HttpAdmissionClient client = new HttpAdmissionClient(config, settings(api.uri(), null), ledger,
                    HttpGatewayProtocol.KANNEL)) {
                assertEquals(FAILED, client.submit(true).orElseThrow().completion().get(5, TimeUnit.SECONDS));
                assertEquals(1, client.problems().get(HttpAdmissionClient.Problem.INVALID_ACKNOWLEDGEMENT));
                assertFalse(query.get().containsKey("dlr-mask"));
                assertFalse(query.get().containsKey("dlr-url"));
            }
        }
    }

    @Test
    void kannelDoesNotLearnProviderIdentityFromCallbacksAndAcknowledgesProvenDuplicates() throws Exception {
        RunConfig config = config(true, 3000, 10);
        MessageLedger ledger = ledger(config);
        String first = ledger.attempt(true);
        String second = ledger.attempt(true);
        for (String token : new String[]{first, second}) {
            ledger.admission(token, ACCEPTED);
            ledger.event(token, PROVIDER_ARRIVAL);
            assertTrue(ledger.assignProviderId(token, token.equals(first) ? "p-1" : "p-2"));
            ledger.event(token, PROVIDER_RESPONSE);
            ledger.event(token, RECEIPT_GENERATED);
        }
        try (HttpReceiptReceiver receiver = new HttpReceiptReceiver(new InetSocketAddress("127.0.0.1", 0),
                "/dlr", 2, 10, true, ledger, HttpGatewayProtocol.KANNEL)) {
            receiver.start();
            String url = "http://127.0.0.1:" + receiver.port() + "/dlr?token=" + first + "&messageId=p-1&status=1";
            assertEquals(400, get(URI.create(url.replace("p-1", "p-2"))));
            assertEquals(400, get(URI.create(url.replace("p-1", "p-unknown"))));
            assertEquals(400, get(URI.create(url.replace(first, "unknown:1"))));
            assertEquals(400, get(URI.create(url + "&messageId=p-1")));
            assertEquals(422, get(URI.create(url.replace("status=1", "status=2"))));
            assertEquals(0, ledger.snapshot().receiptsForwarded());
            assertEquals(200, get(URI.create(url)));
            assertEquals(200, get(URI.create(url)));
            assertEquals(1, ledger.snapshot().receiptsForwarded());
            assertEquals(1, ledger.snapshot().duplicateReceipts());
            assertEquals(4, ledger.snapshot().violations());
        }
    }

    @Test
    void kannelExpiredReceiptHistoryIsAcknowledgedAsLimitedRatherThanProven() throws Exception {
        RunConfig config = config(true, 3000, 10);
        MessageLedger ledger = new MessageLedger(config, "http-test", System::nanoTime, 0);
        String token = ledger.attempt(true);
        ledger.admission(token, ACCEPTED);
        ledger.event(token, PROVIDER_ARRIVAL);
        assertTrue(ledger.assignProviderId(token, "p-1"));
        ledger.event(token, PROVIDER_RESPONSE);
        ledger.event(token, RECEIPT_GENERATED);
        assertEquals(MessageLedger.ReceiptResult.RECORDED, ledger.receiveProviderReceipt(token, "p-1"));
        try (HttpReceiptReceiver receiver = new HttpReceiptReceiver(new InetSocketAddress("127.0.0.1", 0),
                "/dlr", 1, 1, true, ledger, HttpGatewayProtocol.KANNEL)) {
            receiver.start();
            assertEquals(200, get(URI.create("http://127.0.0.1:" + receiver.port()
                    + "/dlr?token=" + token + "&messageId=p-1&status=1")));
            assertEquals(1, receiver.problems().get(HttpReceiptReceiver.Problem.LIMITED_HISTORY));
            assertEquals(1, ledger.eventsBeyondRetention());
            assertEquals(1, ledger.snapshot().receiptsForwarded());
            assertEquals(0, ledger.snapshot().violations());
        }
    }

    private static int get(URI uri) throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(3)).GET().build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode();
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws Exception {
        try (exchange) {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }

    private interface Handler { void handle(HttpExchange exchange) throws Exception; }

    private static class Api implements AutoCloseable {
        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 10);
        final java.util.concurrent.ExecutorService executor = Executors.newFixedThreadPool(2);
        Api(Handler handler) throws Exception {
            server.setExecutor(executor);
            server.createContext("/sendsms", exchange -> {
                try { handler.handle(exchange); }
                catch (Exception exception) { exchange.close(); }
            });
            server.start();
        }
        URI uri() { return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/sendsms"); }
        @Override public void close() {
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
