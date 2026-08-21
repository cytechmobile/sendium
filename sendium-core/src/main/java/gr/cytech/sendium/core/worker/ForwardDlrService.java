package gr.cytech.sendium.core.worker;

import io.quarkus.arc.properties.IfBuildProperty;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@ApplicationScoped
@IfBuildProperty(name = "sendium.dlr.persistence.enabled", stringValue = "true", enableIfMissing = false)
public class ForwardDlrService {
    private static final Logger logger = LoggerFactory.getLogger(ForwardDlrService.class);

    private static final int DUE_BATCH_SIZE = 100;
    private static final int MAX_ATTEMPTS = 10;
    private static final long RETRY_INTERVAL_MS = 120_000;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    private static final int DLR_DELIVERED = 1;
    private static final int DLR_FAILED = 2;
    private static final int DLR_BUFFERED = 4;
    private static final int DLR_SMSC_SUBMIT = 8;

    private static final String DLR_TYPE_PLACEHOLDER = "%d";
    private static final String MSG_ID_PLACEHOLDER = "%s";

    private final DlrService dlrService;
    private final HttpClient httpClient;

    @Inject
    public ForwardDlrService(DlrService dlrService) {
        this(dlrService, newHttpClient());
    }

    ForwardDlrService(DlrService dlrService, HttpClient httpClient) {
        this.dlrService = dlrService;
        this.httpClient = httpClient;
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
            dueDeliveries = dlrService.listDueHttpDeliveries(DUE_BATCH_SIZE);
        } catch (RuntimeException e) {
            logger.error("Unable to list due HTTP DLR deliveries");
            return;
        }

        for (MessageState state : dueDeliveries) {
            try {
                dispatch(state);
                if (Thread.currentThread().isInterrupted()) {
                    return;
                }
            } catch (RuntimeException e) {
                logger.error("Unexpected HTTP DLR dispatch failure for gatewayMsgId={}", state.getGatewayMsgId());
            }
        }
    }

    private void dispatch(MessageState dueState) {
        String gatewayMsgId = dueState.getGatewayMsgId();
        HttpRequest request;
        try {
            request = buildRequest(dueState);
        } catch (RuntimeException e) {
            failInvalidDelivery(gatewayMsgId);
            return;
        }

        Optional<MessageState> started;
        try {
            started = dlrService.startDeliveryAttempt(gatewayMsgId, MessageState.DeliveryChannel.HTTP);
        } catch (RuntimeException e) {
            recordStorageError(gatewayMsgId, 0, "start");
            return;
        }
        if (started.isEmpty()) {
            return;
        }

        int attempt = started.orElseThrow().getDeliveryAttemptCount();
        try {
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() >= 200 && response.statusCode() < 400) {
                completeDelivery(gatewayMsgId, attempt);
            } else {
                handleAttemptFailure(gatewayMsgId, attempt, "http_failure");
            }
        } catch (HttpTimeoutException e) {
            handleAttemptFailure(gatewayMsgId, attempt, "timeout");
        } catch (InterruptedException e) {
            handleAttemptFailure(gatewayMsgId, attempt, "interrupted");
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException e) {
            handleAttemptFailure(gatewayMsgId, attempt, "transport_failure");
        }
    }

    private HttpRequest buildRequest(MessageState state) {
        String callbackTemplate = state.getForwardDlrUrl();
        if (callbackTemplate == null || callbackTemplate.isBlank()) {
            throw new IllegalArgumentException("Missing callback URI");
        }
        String forwardUrl = buildForwardUrl(
                callbackTemplate, state.getGatewayMsgId(), mapToKannelType(state.getStatus()));
        URI uri = URI.create(forwardUrl);
        String scheme = uri.getScheme();
        String normalizedScheme = scheme == null ? "" : scheme.toLowerCase(Locale.ROOT);
        if (uri.getHost() == null || !(normalizedScheme.equals("http") || normalizedScheme.equals("https"))) {
            throw new IllegalArgumentException("Callback URI must use HTTP or HTTPS");
        }
        return HttpRequest.newBuilder()
                .uri(uri)
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();
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
        logger.warn("HTTP DLR delivery attempt failed for gatewayMsgId={} attempt={} outcome={}",
                gatewayMsgId, attempt, result);
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

    private void failInvalidDelivery(String gatewayMsgId) {
        logger.warn("Invalid HTTP DLR callback for gatewayMsgId={}", gatewayMsgId);
        try {
            if (!dlrService.failInvalidDelivery(gatewayMsgId, "invalid_uri")) {
                recordStorageError(gatewayMsgId, 0, "invalid");
            }
        } catch (RuntimeException e) {
            recordStorageError(gatewayMsgId, 0, "invalid");
        }
    }

    private void recordStorageError(String gatewayMsgId, int attempt, String operation) {
        logger.error("HTTP DLR storage update failed for gatewayMsgId={} attempt={} operation={}",
                gatewayMsgId, attempt, operation);
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

    String buildForwardUrl(String baseUrl, String msgId, int kannelType) {
        String result = baseUrl.replace(DLR_TYPE_PLACEHOLDER, String.valueOf(kannelType));
        return result.replace(MSG_ID_PLACEHOLDER, msgId != null ? msgId : "");
    }
}
