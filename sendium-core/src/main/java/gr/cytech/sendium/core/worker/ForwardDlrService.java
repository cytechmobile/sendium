package gr.cytech.sendium.core.worker;

import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@ApplicationScoped
public class ForwardDlrService {
    private static final Logger logger = LoggerFactory.getLogger(ForwardDlrService.class);

    private static final int MAX_RETRIES = 10;
    private static final long RETRY_INTERVAL_MS = 120_000;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    private static final int DLR_DELIVERED = 1;
    private static final int DLR_FAILED = 2;
    private static final int DLR_BUFFERED = 4;
    private static final int DLR_SMSC_SUBMIT = 8;

    private static final String DLR_TYPE_PLACEHOLDER = "%d";
    private static final String MSG_ID_PLACEHOLDER = "%s";

    private final HttpClient httpClient;

    public ForwardDlrService() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(REQUEST_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public void forwardDlr(MessageState state) {
        String forwardUrl = state.getForwardDlrUrl();
        if (forwardUrl == null || forwardUrl.isBlank()) {
            return;
        }

        int kannelType = mapToKannelType(state.getStatus());

        Thread.startVirtualThread(() -> {
            try {
                String finalUrl = buildForwardUrl(forwardUrl, state.getGatewayMsgId(), kannelType);
                doForward(finalUrl, state.getGatewayMsgId(), 1);
            } catch (Exception e) {
                logger.error("Failed to initialize DLR forwarding for gatewayMsgId: {}", state.getGatewayMsgId(), e);
            }
        });
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
        result = result.replace(MSG_ID_PLACEHOLDER, msgId != null ? msgId : "");
        return result;
    }

    private void doForward(String url, String gatewayMsgId, int attempt) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(REQUEST_TIMEOUT)
                    .GET()
                    .build();

            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            int statusCode = response.statusCode();

            if (statusCode >= 200 && statusCode < 400) {
                logger.info("DLR forwarded successfully for gatewayMsgId: {}", gatewayMsgId);
            } else {
                handleFailure(url, gatewayMsgId, attempt, "HTTP " + statusCode);
            }
        } catch (IOException | InterruptedException | RuntimeException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            handleFailure(url, gatewayMsgId, attempt, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private void handleFailure(String url, String gatewayMsgId, int attempt, String error) {
        if (attempt >= MAX_RETRIES) {
            logger.error("DLR forward failed completely after {} retries for gatewayMsgId: {}. Last error: {}",
                    MAX_RETRIES, gatewayMsgId, error);
        } else {
            logger.warn("DLR forward attempt {} failed for gatewayMsgId: {}. Error: {}. Scheduling retry.",
                    attempt, gatewayMsgId, error);
            scheduleRetry(url, gatewayMsgId, attempt);
        }
    }

    private void scheduleRetry(String url, String gatewayMsgId, int attempt) {
        try {
            Thread.sleep(RETRY_INTERVAL_MS);
            doForward(url, gatewayMsgId, attempt + 1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("Retry sleep interrupted for gatewayMsgId: {}", gatewayMsgId);
        }
    }
}