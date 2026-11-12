package gr.cytech.sendium.core.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import gr.cytech.sendium.core.message.DlrReturnMetadata.HttpCallbackFormat;
import gr.cytech.sendium.util.MessageTrace;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.arc.properties.IfBuildProperty;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

@ApplicationScoped
@IfBuildProperty(name = "sendium.dlr.persistence.enabled", stringValue = "true", enableIfMissing = false)
public class ForwardDlrService {
    private static final Logger logger = LoggerFactory.getLogger(ForwardDlrService.class);
    private static final ObjectMapper CALLBACK_JSON = new ObjectMapper();

    private static final int DUE_BATCH_SIZE = 500;
    private static final int MAX_IN_FLIGHT_PER_HOST = 10;
    private static final int MAX_ATTEMPTS = 10;
    private static final long RETRY_INTERVAL_MS = Duration.ofHours(1).toMillis();
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    private static final int DLR_DELIVERED = 1;
    private static final int DLR_FAILED = 2;
    private static final int DLR_BUFFERED = 4;
    private static final int DLR_SMSC_SUBMIT = 8;

    private final DlrService dlrService;
    private final HttpClient httpClient;
    private Counter successfulReturns;
    private Counter failedReturns;

    @Inject
    public ForwardDlrService(DlrService dlrService) {
        this(dlrService, newHttpClient());
    }

    ForwardDlrService(DlrService dlrService, HttpClient httpClient) {
        this.dlrService = dlrService;
        this.httpClient = httpClient;
    }

    /** CDI binds automatically; direct construction can opt in without changing callback behavior. */
    @Inject
    public void bindMetrics(MeterRegistry metrics) {
        successfulReturns = metrics.counter("sendium.dlr.return.attempts", "channel", "http", "result", "success");
        failedReturns = metrics.counter("sendium.dlr.return.attempts", "channel", "http", "result", "failure");
    }

    private void recordReturn(boolean success) {
        if (successfulReturns != null) {
            (success ? successfulReturns : failedReturns).increment();
        }
    }

    static HttpClient newHttpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(REQUEST_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Scheduled(every = "1s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void dispatchDueDeliveries() {
        List<MessageState> dueDeliveries;
        try {
            dueDeliveries = dlrService.claimDueHttpDeliveries(DUE_BATCH_SIZE);
        } catch (RuntimeException e) {
            logger.error("claimDlrCallbacks|error");
            return;
        }

        Map<String, Semaphore> hostLimits = new ConcurrentHashMap<>();
        try (var senders = Executors.newVirtualThreadPerTaskExecutor()) {
            for (MessageState state : dueDeliveries) {
                senders.submit(() -> dispatchSafely(state, hostLimits));
            }
        }
    }

    private void dispatchSafely(MessageState state, Map<String, Semaphore> hostLimits) {
        try {
            dispatch(state, hostLimits);
        } catch (RuntimeException e) {
            logger.error("dlrCallback|error|gatewayMsgId:{}|reason:unexpected_dispatch_failure", MessageTrace.value(state.getGatewayMsgId()));
        }
    }

    private void dispatch(MessageState dueState, Map<String, Semaphore> hostLimits) {
        String gatewayMsgId = dueState.getGatewayMsgId();
        // Submission rejection is Kannel bit 16, not a failed handset receipt (bit 2).
        int eventType = "provider_rejected".equals(dueState.getLastDeliveryResult()) ?
                16 : mapToKannelType(dueState.getStatus());
        if (dueState.getHttpCallbackFormat() == HttpCallbackFormat.KANNEL_GET && (dueState.getDlrMask() & eventType) == 0) {
            completeDelivery(gatewayMsgId, dueState.getDeliveryAttemptCount());
            return;
        }
        HttpRequest request;
        try {
            request = buildRequest(dueState);
        } catch (RuntimeException e) {
            failInvalidDelivery(gatewayMsgId, dueState.getDeliveryAttemptCount());
            return;
        }

        int attempt = dueState.getDeliveryAttemptCount();
        Semaphore hostLimit = hostLimits.computeIfAbsent(
                request.uri().getHost().toLowerCase(Locale.ROOT), ignored -> new Semaphore(MAX_IN_FLIGHT_PER_HOST));
        boolean acquired = false;
        boolean attempted = false;
        try {
            hostLimit.acquire();
            acquired = true;
            attempted = true;
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                recordReturn(true);
                completeDelivery(gatewayMsgId, attempt);
            } else {
                recordReturn(false);
                handleAttemptFailure(gatewayMsgId, attempt, "http_failure");
            }
        } catch (HttpTimeoutException e) {
            recordReturn(false);
            handleAttemptFailure(gatewayMsgId, attempt, "timeout");
        } catch (InterruptedException e) {
            if (attempted) {
                recordReturn(false);
            }
            handleAttemptFailure(gatewayMsgId, attempt, "interrupted");
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException e) {
            if (attempted) {
                recordReturn(false);
            }
            handleAttemptFailure(gatewayMsgId, attempt, "transport_failure");
        } finally {
            if (acquired) {
                hostLimit.release();
            }
        }
    }

    private HttpRequest buildRequest(MessageState state) {
        String callbackTemplate = state.getForwardDlrUrl();
        if (callbackTemplate == null || callbackTemplate.isBlank()) {
            throw new IllegalArgumentException("Missing callback URI");
        }
        boolean jsonPost = state.getHttpCallbackFormat() == HttpCallbackFormat.JSON_POST;
        String forwardUrl = jsonPost ? callbackTemplate : buildForwardUrl(
                callbackTemplate, state.getGatewayMsgId(), state.getProviderMessageId(), mapToKannelType(state.getStatus()));
        URI uri = URI.create(forwardUrl);
        String scheme = uri.getScheme();
        String normalizedScheme = scheme == null ? "" : scheme.toLowerCase(Locale.ROOT);
        if (uri.getHost() == null || !(normalizedScheme.equals("http") || normalizedScheme.equals("https"))) {
            throw new IllegalArgumentException("Callback URI must use HTTP or HTTPS");
        }
        var request = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(REQUEST_TIMEOUT);
        if (jsonPost) {
            String status = switch (state.getStatus()) {
                case DELIVERED -> "delivered";
                case FAILED -> "failed";
                default -> throw new IllegalArgumentException("Native callback requires a terminal outcome");
            };
            var payload = CALLBACK_JSON.createObjectNode()
                    .put("messageId", state.getGatewayMsgId())
                    .put("status", status)
                    .put("errorCode", state.getErrorCode());
            return request.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                    .build();
        }
        return request.GET().build();
    }

    private void completeDelivery(String gatewayMsgId, int attempt) {
        try {
            if (!dlrService.completeDelivery(gatewayMsgId, attempt)) {
                recordStorageError(gatewayMsgId, attempt, "complete");
            }
        } catch (RuntimeException e) {
            recordStorageError(gatewayMsgId, attempt, "complete");
        }
    }

    private void handleAttemptFailure(String gatewayMsgId, int attempt, String result) {
        logger.warn("dlrCallback|{}|gatewayMsgId:{}|attempt:{}|reason:{}",
                attempt < MAX_ATTEMPTS ? "retry" : "error", MessageTrace.value(gatewayMsgId), attempt, MessageTrace.value(result));
        try {
            boolean updated;
            if (attempt < MAX_ATTEMPTS) {
                updated = dlrService.retryDelivery(
                        gatewayMsgId, attempt, result, System.currentTimeMillis() + RETRY_INTERVAL_MS);
            } else {
                updated = dlrService.failDelivery(gatewayMsgId, attempt, result);
            }
            if (!updated) {
                recordStorageError(gatewayMsgId, attempt, "finish");
            }
        } catch (RuntimeException e) {
            recordStorageError(gatewayMsgId, attempt, "finish");
        }
    }

    private void failInvalidDelivery(String gatewayMsgId, int attempt) {
        logger.warn("dlrCallback|error|gatewayMsgId:{}|reason:invalid_uri", MessageTrace.value(gatewayMsgId));
        try {
            if (!dlrService.failInvalidDelivery(gatewayMsgId, attempt, "invalid_uri")) {
                recordStorageError(gatewayMsgId, attempt, "invalid");
            }
        } catch (RuntimeException e) {
            recordStorageError(gatewayMsgId, attempt, "invalid");
        }
    }

    private void recordStorageError(String gatewayMsgId, int attempt, String operation) {
        logger.error("updateDlrCallback|error|gatewayMsgId:{}|attempt:{}|action:{}",
                MessageTrace.value(gatewayMsgId), attempt, MessageTrace.value(operation));
    }

    int mapToKannelType(MessageState.MessageStatus status) {
        if (status == null) {
            return DLR_BUFFERED;
        }
        return switch (status) {
            case ACCEPTED -> DLR_BUFFERED;
            case SENT -> DLR_SMSC_SUBMIT;
            case DELIVERED -> DLR_DELIVERED;
            case FAILED -> DLR_FAILED;
        };
    }

    String buildForwardUrl(String template, String gatewayId, String providerId, int kannelType) {
        StringBuilder result = new StringBuilder(template.length());
        for (int index = 0; index < template.length(); index++) {
            char current = template.charAt(index);
            if (current != '%' || index + 1 == template.length()) {
                result.append(current);
                continue;
            }
            char token = template.charAt(++index);
            switch (token) {
                case 'I' -> result.append(urlEncode(gatewayId));
                case 'F' -> result.append(urlEncode(providerId));
                case 'd' -> result.append(kannelType);
                case '%' -> result.append('%');
                default -> result.append('%').append(token);
            }
        }
        return result.toString();
    }

    private static String urlEncode(String value) {
        return value == null ? "" : URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
