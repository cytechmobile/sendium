package gr.cytech.sendium.core.worker;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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

    @BeforeEach
    void setUp() {
        service = new ForwardDlrService(dlrService, httpClient);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void schedulerUsesBoundedBatchAndDoesNothingWhenNoDeliveryIsDue() throws Exception {
        when(dlrService.listDueHttpDeliveries(100)).thenReturn(List.of());

        service.dispatchDueDeliveries();

        verify(dlrService).listDueHttpDeliveries(100);
        verifyNoInteractions(httpClient);
    }

    @Test
    void successfulResponseCompletesExpectedAttempt() throws Exception {
        MessageState due = dueState("https://example.test/dlr?id=%s&type=%d");
        dueAttempt(due, 1);
        respondWith(204);
        when(dlrService.completeDelivery(GATEWAY_ID, 1)).thenReturn(true);

        service.dispatchDueDeliveries();

        ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(request.capture(), anyBodyHandler());
        assertThat(request.getValue().uri().toString())
                .isEqualTo("https://example.test/dlr?id=" + GATEWAY_ID + "&type=1");
        assertThat(request.getValue().timeout()).contains(java.time.Duration.ofSeconds(5));
        verify(dlrService).completeDelivery(GATEWAY_ID, 1);
        var order = inOrder(dlrService, httpClient);
        order.verify(dlrService).startDeliveryAttempt(GATEWAY_ID, MessageState.DeliveryChannel.HTTP);
        order.verify(httpClient).send(any(HttpRequest.class), anyBodyHandler());
    }

    @Test
    void directRedirectCompletesAndIsNotFollowed() throws Exception {
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
        dueAttempt(due, 1);
        when(dlrService.completeDelivery(GATEWAY_ID, 1)).thenReturn(true);
        service = new ForwardDlrService(dlrService, ForwardDlrService.newHttpClient());

        service.dispatchDueDeliveries();

        verify(dlrService).completeDelivery(GATEWAY_ID, 1);
        assertThat(redirectTargetRequests).hasValue(0);
        assertThat(ForwardDlrService.newHttpClient().followRedirects()).isEqualTo(HttpClient.Redirect.NEVER);
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
    }

    @Test
    void interruptionSchedulesRetryRestoresInterruptAndStopsBatch() throws Exception {
        MessageState due = dueState("https://example.test/dlr");
        String laterGatewayId = "1e5fc768-c60d-4417-95bf-d39642381a1c";
        MessageState later = new MessageState(laterGatewayId, "account", "system", "source", "destination",
                "https://example.test/later");
        MessageState started = dueState(due.getForwardDlrUrl());
        started.setDeliveryAttemptCount(5);
        when(dlrService.listDueHttpDeliveries(100)).thenReturn(List.of(due, later));
        when(dlrService.startDeliveryAttempt(GATEWAY_ID, MessageState.DeliveryChannel.HTTP))
                .thenReturn(Optional.of(started));
        when(httpClient.send(any(HttpRequest.class), anyBodyHandler()))
                .thenThrow(new InterruptedException("interrupted"));
        when(dlrService.retryDelivery(eq(GATEWAY_ID), eq(5), eq("interrupted"), anyLong()))
                .thenReturn(true);

        try {
            service.dispatchDueDeliveries();

            verify(dlrService).retryDelivery(eq(GATEWAY_ID), eq(5), eq("interrupted"), anyLong());
            verify(dlrService, never()).startDeliveryAttempt(laterGatewayId, MessageState.DeliveryChannel.HTTP);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void invalidUriFailsWithoutStartingAnAttempt() {
        MessageState due = dueState("https://example.test/%ZZ?secret=value");
        when(dlrService.listDueHttpDeliveries(100)).thenReturn(List.of(due));
        when(dlrService.failInvalidDelivery(GATEWAY_ID, "invalid_uri")).thenReturn(true);

        service.dispatchDueDeliveries();

        verify(dlrService).failInvalidDelivery(GATEWAY_ID, "invalid_uri");
        verify(dlrService, never()).startDeliveryAttempt(any(), any());
        verifyNoInteractions(httpClient);
    }

    @Test
    void activeAttemptIsSkippedBeforeSending() {
        MessageState due = dueState("https://example.test/dlr");
        when(dlrService.listDueHttpDeliveries(100)).thenReturn(List.of(due));
        when(dlrService.startDeliveryAttempt(GATEWAY_ID, MessageState.DeliveryChannel.HTTP))
                .thenReturn(Optional.empty());

        service.dispatchDueDeliveries();

        verifyNoInteractions(httpClient);
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
    }

    @Test
    void schedulerStorageFailureDoesNotSend() {
        when(dlrService.listDueHttpDeliveries(100)).thenThrow(new DlrStorageException("database unavailable"));

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
        assertThat(service.buildForwardUrl("https://example.test?id=%s&type=%d", "msg-1", 2))
                .isEqualTo("https://example.test?id=msg-1&type=2");
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
        assertThat(nextAttempt.getValue()).isBetween(beforeFailure + 120_000, System.currentTimeMillis() + 120_000);
        assertThat(deliveryResult().getValue()).isEqualTo("http_failure");
    }

    private ArgumentCaptor<String> deliveryResult() {
        ArgumentCaptor<String> result = ArgumentCaptor.forClass(String.class);
        verify(dlrService).retryDelivery(eq(GATEWAY_ID), anyInt(), result.capture(), anyLong());
        return result;
    }

    private void dueAttempt(MessageState due, int attempt) {
        MessageState started = dueState(due.getForwardDlrUrl());
        started.setDeliveryAttemptCount(attempt);
        when(dlrService.listDueHttpDeliveries(100)).thenReturn(List.of(due));
        when(dlrService.startDeliveryAttempt(GATEWAY_ID, MessageState.DeliveryChannel.HTTP))
                .thenReturn(Optional.of(started));
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
