package gr.cytech.sendium.core.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import gr.cytech.sendium.core.message.DlrReturnMetadata.HttpCallbackFormat;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ForwardDlrServiceTest {
    private static final String GATEWAY_ID = "3fdac55b-a953-4a36-8d0f-0273e3537502";

    @Mock
    DlrService dlrService;

    @Mock
    HttpClient httpClient;

    private ForwardDlrService service;
    private HttpServer server;
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();

    @BeforeEach
    void setUp() {
        service = new ForwardDlrService(dlrService, httpClient);
        service.bindMetrics(metrics);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
        metrics.close();
    }

    @Test
    void schedulerUsesBoundedBatchAndDoesNothingWhenNoDeliveryIsDue() throws Exception {
        when(dlrService.claimDueHttpDeliveries(500)).thenReturn(List.of());

        service.dispatchDueDeliveries();

        verify(dlrService).claimDueHttpDeliveries(500);
        verifyNoInteractions(httpClient);
    }

    @Test
    void largeBatchKeepsPerHostConcurrencyBoundedAndRetriesOnlyTheFailedCallback() throws Exception {
        List<MessageState> deliveries = IntStream.range(0, 500).mapToObj(index -> {
            var state = new MessageState("message-" + index, "account", "system", "source", "destination",
                    "https://example.test/dlr?id=%I");
            state.setStatus(MessageState.MessageStatus.DELIVERED);
            state.setDeliveryChannel(MessageState.DeliveryChannel.HTTP);
            state.setDeliveryAttemptCount(1);
            return state;
        }).toList();
        when(dlrService.claimDueHttpDeliveries(500)).thenReturn(deliveries);
        when(dlrService.completeDelivery(anyString(), eq(1))).thenReturn(true);
        when(dlrService.retryDelivery(eq("message-42"), eq(1), eq("http_failure"), anyLong())).thenReturn(true);
        HttpResponse<Void> success = mock(HttpResponse.class);
        HttpResponse<Void> failure = mock(HttpResponse.class);
        when(success.statusCode()).thenReturn(204);
        when(failure.statusCode()).thenReturn(503);
        var firstWave = new CountDownLatch(10);
        var releaseCallbacks = new CountDownLatch(1);
        var active = new AtomicInteger();
        var peakActive = new AtomicInteger();
        var started = new AtomicInteger();
        when(httpClient.send(any(HttpRequest.class), anyBodyHandler())).thenAnswer(invocation -> {
            int concurrent = active.incrementAndGet();
            peakActive.accumulateAndGet(concurrent, Math::max);
            started.incrementAndGet();
            firstWave.countDown();
            try {
                assertThat(releaseCallbacks.await(10, TimeUnit.SECONDS)).isTrue();
                HttpRequest request = invocation.getArgument(0);
                return request.uri().getQuery().equals("id=message-42") ? failure : success;
            } finally {
                active.decrementAndGet();
            }
        });

        try (var dispatcher = Executors.newSingleThreadExecutor()) {
            var dispatched = dispatcher.submit(service::dispatchDueDeliveries);
            try {
                assertThat(firstWave.await(10, TimeUnit.SECONDS)).isTrue();
                assertThat(started).hasValue(10);
                assertThat(peakActive).hasValue(10);
                assertThat(dispatched.isDone()).isFalse();
            } finally {
                releaseCallbacks.countDown();
            }
            dispatched.get(10, TimeUnit.SECONDS);
        }

        assertThat(started).hasValue(500);
        assertThat(peakActive).hasValue(10);
        assertThat(active).hasValue(0);
        ArgumentCaptor<String> completed = ArgumentCaptor.forClass(String.class);
        verify(dlrService, times(499)).completeDelivery(completed.capture(), eq(1));
        assertThat(completed.getAllValues()).doesNotHaveDuplicates().doesNotContain("message-42");
        assertThat(completed.getAllValues()).containsExactlyInAnyOrderElementsOf(
                deliveries.stream().map(MessageState::getGatewayMsgId).filter(id -> !id.equals("message-42")).toList());
        verify(dlrService).retryDelivery(eq("message-42"), eq(1), eq("http_failure"), anyLong());
        verify(dlrService, never()).failDelivery(anyString(), anyInt(), anyString());
        assertThat(returns("success")).isEqualTo(499);
        assertThat(returns("failure")).isEqualTo(1);
    }

    @Test
    void successfulResponseCompletesExpectedAttempt() throws Exception {
        MessageState due = dueState("https://example.test/dlr?id=%I&provider=%F&type=%d");
        due.setProviderMessageId("provider-42");
        dueAttempt(due, 1);
        respondWith(204);
        when(dlrService.completeDelivery(GATEWAY_ID, 1)).thenReturn(true);

        service.dispatchDueDeliveries();

        ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(request.capture(), anyBodyHandler());
        assertThat(request.getValue().uri().toString())
                .isEqualTo("https://example.test/dlr?id=" + GATEWAY_ID + "&provider=provider-42&type=1");
        assertThat(request.getValue().timeout()).contains(java.time.Duration.ofSeconds(5));
        assertThat(request.getValue().method()).isEqualTo("GET");
        verify(dlrService).completeDelivery(GATEWAY_ID, 1);
        var order = inOrder(dlrService, httpClient);
        order.verify(dlrService).claimDueHttpDeliveries(500);
        order.verify(httpClient).send(any(HttpRequest.class), anyBodyHandler());
        assertThat(returns("success")).isEqualTo(1);
        assertThat(returns("failure")).isZero();
    }

    @Test
    void unrequestedTerminalEventsCompleteWithoutSendingOrRetrying() {
        for (int mask : new int[]{0, 2}) {
            MessageState due = dueState("https://example.test/dlr?id=%I");
            due.setDlrMask(mask);
            dueAttempt(due, 1);
            when(dlrService.completeDelivery(GATEWAY_ID, 1)).thenReturn(true);
            service.dispatchDueDeliveries();
        }
        verify(dlrService, times(2)).completeDelivery(GATEWAY_ID, 1);
        verifyNoInteractions(httpClient);
        assertThat(returns("success")).isZero();
        assertThat(returns("failure")).isZero();
    }

    @Test
    void submissionRejectionIsNotMistakenForFailedDeliveryReceipt() {
        MessageState due = dueState("https://example.test/dlr?id=%I");
        due.setStatus(MessageState.MessageStatus.FAILED);
        due.setDlrMask(3);
        due.setLastDeliveryResult("provider_rejected");
        dueAttempt(due, 1);
        when(dlrService.completeDelivery(GATEWAY_ID, 1)).thenReturn(true);
        service.dispatchDueDeliveries();
        verify(dlrService).completeDelivery(GATEWAY_ID, 1);
        verifyNoInteractions(httpClient);
    }

    @Test
    void deliveredOnlyMaskDoesNotForwardFailedReceipt() {
        MessageState due = dueState("https://example.test/dlr?id=%I");
        due.setStatus(MessageState.MessageStatus.FAILED);
        due.setDlrMask(1);
        dueAttempt(due, 1);
        when(dlrService.completeDelivery(GATEWAY_ID, 1)).thenReturn(true);
        service.dispatchDueDeliveries();
        verify(dlrService).completeDelivery(GATEWAY_ID, 1);
        verifyNoInteractions(httpClient);
    }

    @Test
    void failedTerminalReceiptUsesProviderIdentityAndFailureType() throws Exception {
        MessageState due = dueState("https://example.test/dlr?id=%I&provider=%F&type=%d");
        due.setStatus(MessageState.MessageStatus.FAILED);
        due.setDlrMask(2);
        due.setProviderMessageId("provider&failed");
        dueAttempt(due, 1);
        respondWith(200);
        when(dlrService.completeDelivery(GATEWAY_ID, 1)).thenReturn(true);

        service.dispatchDueDeliveries();

        ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(request.capture(), anyBodyHandler());
        assertThat(request.getValue().uri().toString())
                .isEqualTo("https://example.test/dlr?id=" + GATEWAY_ID + "&provider=provider%26failed&type=2");
        verify(dlrService).completeDelivery(GATEWAY_ID, 1);
    }

    @ParameterizedTest
    @EnumSource(HttpCallbackFormat.class)
    void directRedirectSchedulesRetryAndIsNotFollowed(HttpCallbackFormat format) throws Exception {
        AtomicInteger redirectTargetRequests = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "/target");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/target", exchange -> {
            redirectTargetRequests.incrementAndGet();
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        MessageState due = dueState("http://" + server.getAddress().getHostString() + ':'
                + server.getAddress().getPort() + "/redirect");
        due.setHttpCallbackFormat(format);
        dueAttempt(due, 1);
        when(dlrService.retryDelivery(eq(GATEWAY_ID), eq(1), eq("http_failure"), anyLong()))
                .thenReturn(true);
        service = new ForwardDlrService(dlrService, ForwardDlrService.newHttpClient());
        service.bindMetrics(metrics);

        service.dispatchDueDeliveries();

        verify(dlrService).retryDelivery(eq(GATEWAY_ID), eq(1), eq("http_failure"), anyLong());
        verify(dlrService, never()).completeDelivery(GATEWAY_ID, 1);
        assertThat(redirectTargetRequests).hasValue(0);
        assertThat(ForwardDlrService.newHttpClient().followRedirects()).isEqualTo(HttpClient.Redirect.NEVER);
    }

    @ParameterizedTest
    @EnumSource(value = MessageState.MessageStatus.class, names = {"DELIVERED", "FAILED"})
    void nativeCallbackPostsOnlyTheDefinedJsonFieldsAndCompletesAfterSuccess(MessageState.MessageStatus status) throws Exception {
        var method = new AtomicReference<String>();
        var contentType = new AtomicReference<String>();
        var body = new AtomicReference<String>();
        var query = new AtomicReference<String>();
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/callback", exchange -> {
            method.set(exchange.getRequestMethod());
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            query.set(exchange.getRequestURI().getRawQuery());
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        var due = dueState("http://" + server.getAddress().getHostString() + ':'
                + server.getAddress().getPort() + "/callback?token=a%20b");
        due.setHttpCallbackFormat(HttpCallbackFormat.JSON_POST);
        due.setStatus(status);
        due.setDlrMask(0);
        if (status == MessageState.MessageStatus.FAILED) {
            due.setLastDeliveryResult("provider_rejected");
        }
        due.setErrorCode(status == MessageState.MessageStatus.FAILED ? "\"failure\"\\code" : null);
        dueAttempt(due, 1);
        when(dlrService.completeDelivery(GATEWAY_ID, 1)).thenReturn(true);
        service = new ForwardDlrService(dlrService, ForwardDlrService.newHttpClient());
        service.bindMetrics(metrics);

        service.dispatchDueDeliveries();

        assertThat(method.get()).isEqualTo("POST");
        assertThat(contentType.get()).isEqualTo("application/json");
        assertThat(query.get()).isEqualTo("token=a%20b");
        var json = new ObjectMapper().readTree(body.get());
        assertThat(json.size()).isEqualTo(3);
        assertThat(json.get("messageId").asText()).isEqualTo(GATEWAY_ID);
        assertThat(json.get("status").asText()).isEqualTo(status == MessageState.MessageStatus.DELIVERED ? "delivered" : "failed");
        if (status == MessageState.MessageStatus.DELIVERED) {
            assertThat(json.get("errorCode").isNull()).isTrue();
        } else {
            assertThat(json.get("errorCode").asText()).isEqualTo(due.getErrorCode());
        }
        verify(dlrService).completeDelivery(GATEWAY_ID, 1);
    }

    @Test
    void nativeCallbackRetriesTheSameMessageWithoutChangingItsFormat() throws Exception {
        var due = dueState("https://example.test/callback");
        due.setHttpCallbackFormat(HttpCallbackFormat.JSON_POST);
        dueAttempt(due, 2);
        respondWith(503);
        when(dlrService.retryDelivery(eq(GATEWAY_ID), eq(2), eq("http_failure"), anyLong())).thenReturn(true);

        service.dispatchDueDeliveries();

        var request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(request.capture(), anyBodyHandler());
        assertThat(request.getValue().method()).isEqualTo("POST");
        verify(dlrService).retryDelivery(eq(GATEWAY_ID), eq(2), eq("http_failure"), anyLong());
        verify(dlrService, never()).completeDelivery(anyString(), anyInt());
        assertThat(due.getHttpCallbackFormat()).isEqualTo(HttpCallbackFormat.JSON_POST);
    }

    @Test
    void clientErrorSchedulesRetryWithNormalizedResult() throws Exception {
        assertHttpFailureSchedulesRetry(404);
    }

    @Test
    void serverErrorSchedulesRetryWithNormalizedResult() throws Exception {
        assertHttpFailureSchedulesRetry(503);
    }

    @Test
    void tenthFailureMarksDeliveryFailed() throws Exception {
        MessageState due = dueState("https://example.test/dlr");
        dueAttempt(due, 10);
        respondWith(500);
        when(dlrService.failDelivery(GATEWAY_ID, 10, "http_failure")).thenReturn(true);

        service.dispatchDueDeliveries();

        verify(dlrService).failDelivery(GATEWAY_ID, 10, "http_failure");
        verify(dlrService, never()).retryDelivery(eq(GATEWAY_ID), eq(10), any(), anyLong());
    }

    @Test
    void timeoutSchedulesRetry() throws Exception {
        MessageState due = dueState("https://secret.example.test/dlr?token=do-not-expose");
        dueAttempt(due, 2);
        when(httpClient.send(any(HttpRequest.class), anyBodyHandler()))
                .thenThrow(new HttpTimeoutException("request timed out"));
        when(dlrService.retryDelivery(eq(GATEWAY_ID), eq(2), eq("timeout"), anyLong()))
                .thenReturn(true);

        service.dispatchDueDeliveries();

        verify(dlrService).retryDelivery(eq(GATEWAY_ID), eq(2), eq("timeout"), anyLong());
        assertThat(returns("failure")).isEqualTo(1);
        assertThat(returns("success")).isZero();
        assertThat(deliveryResult().getValue()).doesNotContain("secret", "token", "http");
    }

    @Test
    void ioFailureSchedulesTransportRetry() throws Exception {
        MessageState due = dueState("https://example.test/dlr");
        dueAttempt(due, 3);
        when(httpClient.send(any(HttpRequest.class), anyBodyHandler()))
                .thenThrow(new IOException("connection refused"));
        when(dlrService.retryDelivery(eq(GATEWAY_ID), eq(3), eq("transport_failure"), anyLong()))
                .thenReturn(true);

        service.dispatchDueDeliveries();

        verify(dlrService).retryDelivery(eq(GATEWAY_ID), eq(3), eq("transport_failure"), anyLong());
        assertThat(returns("failure")).isEqualTo(1);
    }

    @Test
    void runtimeTransportFailureSchedulesRetry() throws Exception {
        MessageState due = dueState("https://example.test/dlr");
        dueAttempt(due, 4);
        when(httpClient.send(any(HttpRequest.class), anyBodyHandler()))
                .thenThrow(new IllegalStateException("transport unavailable"));
        when(dlrService.retryDelivery(eq(GATEWAY_ID), eq(4), eq("transport_failure"), anyLong()))
                .thenReturn(true);

        service.dispatchDueDeliveries();

        verify(dlrService).retryDelivery(eq(GATEWAY_ID), eq(4), eq("transport_failure"), anyLong());
        assertThat(returns("failure")).isEqualTo(1);
    }

    @Test
    void senderInterruptionSchedulesRetryWithoutInterruptingDispatcher() throws Exception {
        MessageState due = dueState("https://example.test/dlr");
        dueAttempt(due, 5);
        when(httpClient.send(any(HttpRequest.class), anyBodyHandler()))
                .thenThrow(new InterruptedException("interrupted"));
        when(dlrService.retryDelivery(eq(GATEWAY_ID), eq(5), eq("interrupted"), anyLong()))
                .thenReturn(true);

        service.dispatchDueDeliveries();

        verify(dlrService).retryDelivery(eq(GATEWAY_ID), eq(5), eq("interrupted"), anyLong());
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
        assertThat(returns("failure")).isEqualTo(1);
    }

    @Test
    void invalidClaimedUriFailsExpectedAttempt() {
        MessageState due = dueState("https://example.test/%ZZ?secret=value");
        dueAttempt(due, 1);
        when(dlrService.failInvalidDelivery(GATEWAY_ID, 1, "invalid_uri")).thenReturn(true);

        service.dispatchDueDeliveries();

        verify(dlrService).failInvalidDelivery(GATEWAY_ID, 1, "invalid_uri");
        verifyNoInteractions(httpClient);
        assertThat(returns("success")).isZero();
        assertThat(returns("failure")).isZero();
    }

    @Test
    void completionStorageFailureLeavesDeliveryForLaterRun() throws Exception {
        MessageState due = dueState("https://example.test/dlr");
        dueAttempt(due, 1);
        respondWith(200);
        doThrow(new DlrStorageException("database unavailable"))
                .when(dlrService).completeDelivery(GATEWAY_ID, 1);

        service.dispatchDueDeliveries();

        verify(dlrService).completeDelivery(GATEWAY_ID, 1);
        assertThat(returns("success")).isEqualTo(1);
        assertThat(returns("failure")).isZero();
    }

    @Test
    void schedulerStorageFailureDoesNotSend() {
        when(dlrService.claimDueHttpDeliveries(500)).thenThrow(new DlrStorageException("database unavailable"));

        service.dispatchDueDeliveries();

        verifyNoInteractions(httpClient);
    }

    @Test
    void statusAndUrlPlaceholderMappingsRemainStable() {
        assertThat(service.mapToKannelType(MessageState.MessageStatus.ACCEPTED)).isEqualTo(4);
        assertThat(service.mapToKannelType(MessageState.MessageStatus.SENT)).isEqualTo(8);
        assertThat(service.mapToKannelType(MessageState.MessageStatus.DELIVERED)).isEqualTo(1);
        assertThat(service.mapToKannelType(MessageState.MessageStatus.FAILED)).isEqualTo(2);
        assertThat(service.mapToKannelType(null)).isEqualTo(4);
        assertThat(service.buildForwardUrl("https://example.test?id=%I&provider=%F&type=%d", "msg-1", "smsc-2", 2))
                .isEqualTo("https://example.test?id=msg-1&provider=smsc-2&type=2");
    }

    @Test
    void substitutionEncodesIdsWithoutReinterpretingTheirContents() {
        assertThat(service.buildForwardUrl("https://example.test/%I?provider=%F&again=%F", "gateway/id",
                "a b+&/é%I", 1))
                .isEqualTo("https://example.test/gateway%2Fid?provider=a+b%2B%26%2F%C3%A9%25I"
                        + "&again=a+b%2B%26%2F%C3%A9%25I");
    }

    @Test
    void percentEscapesAndUnsupportedTokensFollowPinnedKannelTemplateRules() {
        assertThat(service.buildForwardUrl("https://example.test/?space=%20&pct=%%25&token=%%I"
                + "&unknown=%X&old=%s&tail=%", "gateway", "provider", 1))
                .isEqualTo("https://example.test/?space=%20&pct=%25&token=%I&unknown=%X&old=%s&tail=%");
    }

    @Test
    void unavailableIdsExpandToEmptyValues() {
        assertThat(service.buildForwardUrl("https://example.test/?id=%I&provider=%F&type=%d", null, null, 2))
                .isEqualTo("https://example.test/?id=&provider=&type=2");
    }

    private void assertHttpFailureSchedulesRetry(int statusCode) throws Exception {
        MessageState due = dueState("https://secret.example.test/dlr?token=do-not-expose");
        dueAttempt(due, 1);
        respondWith(statusCode);
        when(dlrService.retryDelivery(eq(GATEWAY_ID), eq(1), eq("http_failure"), anyLong()))
                .thenReturn(true);
        long beforeFailure = System.currentTimeMillis();

        service.dispatchDueDeliveries();

        ArgumentCaptor<Long> nextAttempt = ArgumentCaptor.forClass(Long.class);
        verify(dlrService).retryDelivery(eq(GATEWAY_ID), eq(1), eq("http_failure"), nextAttempt.capture());
        long retryIntervalMillis = java.time.Duration.ofHours(1).toMillis();
        assertThat(nextAttempt.getValue()).isBetween(
                beforeFailure + retryIntervalMillis, System.currentTimeMillis() + retryIntervalMillis);
        assertThat(deliveryResult().getValue()).isEqualTo("http_failure");
    }

    private ArgumentCaptor<String> deliveryResult() {
        ArgumentCaptor<String> result = ArgumentCaptor.forClass(String.class);
        verify(dlrService).retryDelivery(eq(GATEWAY_ID), anyInt(), result.capture(), anyLong());
        return result;
    }

    private double returns(String result) {
        return metrics.get("sendium.dlr.return.attempts").tag("channel", "http").tag("result", result).counter().count();
    }

    private void dueAttempt(MessageState due, int attempt) {
        due.setDeliveryAttemptCount(attempt);
        when(dlrService.claimDueHttpDeliveries(500)).thenReturn(List.of(due));
    }

    private MessageState dueState(String callbackUrl) {
        MessageState state = new MessageState(GATEWAY_ID, "account", "system", "source", "destination",
                callbackUrl);
        state.setStatus(MessageState.MessageStatus.DELIVERED);
        state.setDeliveryChannel(MessageState.DeliveryChannel.HTTP);
        state.setDeliveryStatus(MessageState.DeliveryStatus.PENDING);
        return state;
    }

    private void respondWith(int statusCode) throws Exception {
        HttpResponse<Void> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(statusCode);
        when(httpClient.send(any(HttpRequest.class), anyBodyHandler())).thenReturn(response);
    }

    @SuppressWarnings("unchecked")
    private HttpResponse.BodyHandler<Void> anyBodyHandler() {
        return any(HttpResponse.BodyHandler.class);
    }

}
