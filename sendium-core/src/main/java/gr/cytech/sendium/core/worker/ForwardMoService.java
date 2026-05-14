package gr.cytech.sendium.core.worker;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

@ApplicationScoped
public class ForwardMoService {
    private static final Logger logger = LoggerFactory.getLogger(ForwardMoService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final int MAX_RETRIES = 10;
    private static final long RETRY_INTERVAL_MS = 120_000;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    private static final String PH_FROM = "%p";
    private static final String PH_TO = "%P";
    private static final String PH_TEXT = "%a";
    private static final String PH_TIMESTAMP = "%t";
    private static final String PH_INGATEWAY = "%i";
    private static final String PH_MESSAGE_CENTER = "%I";
    private static final String PH_DATA_CODING = "%o";

    private final HttpClient httpClient;

    public ForwardMoService() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(REQUEST_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public void forwardMo(String forwardUrl, MoContext ctx) {
        forwardMo(forwardUrl, ctx, ForwardFormat.FORM);
    }

    public void forwardMo(String forwardUrl, MoContext ctx, ForwardFormat format) {
        if (forwardUrl == null || forwardUrl.isBlank()) {
            return;
        }

        Thread.startVirtualThread(() -> {
            try {
                String finalUrl = buildForwardUrl(forwardUrl, ctx);
                doForward(finalUrl, ctx, 1, format);
            } catch (Exception e) {
                logger.error("Failed to initialize MO forwarding for from: {}", maskPhone(ctx.from()), e);
            }
        });
    }

    String buildForwardUrl(String template, MoContext ctx) {
        // Encode values BEFORE replacing them in the URL to prevent malformed URIs
        String result = template.replace(PH_FROM, urlEncode(ctx.from()));
        result = result.replace(PH_TO, urlEncode(ctx.to()));
        result = result.replace(PH_TEXT, urlEncode(ctx.text()));
        result = result.replace(PH_TIMESTAMP, urlEncode(ctx.timestamp()));
        result = result.replace(PH_INGATEWAY, urlEncode(ctx.ingateway()));
        result = result.replace(PH_MESSAGE_CENTER, urlEncode(ctx.messageCenter()));
        result = result.replace(PH_DATA_CODING, String.valueOf(ctx.dataCoding()));
        return result;
    }

    private void doForward(String url, MoContext ctx, int attempt, ForwardFormat format) {
        try {
            String body = format == ForwardFormat.JSON ? buildJsonBody(ctx) : buildFormBody(ctx);
            String contentType = format == ForwardFormat.JSON ? "application/json" : "application/x-www-form-urlencoded";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", contentType)
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            int statusCode = response.statusCode();

            if (statusCode >= 200 && statusCode < 400) {
                logger.info("MO forwarded successfully for from: {}", maskPhone(ctx.from()));
            } else {
                handleFailure(url, ctx, attempt, "HTTP " + statusCode, format);
            }

        } catch (IOException | InterruptedException | RuntimeException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            handleFailure(url, ctx, attempt, e.getClass().getSimpleName() + ": " + e.getMessage(), format);
        }
    }

    String buildJsonBody(MoContext ctx) {
        try {
            return MAPPER.writeValueAsString(ctx);
        } catch (JsonProcessingException e) {
            // Throwing an exception here ensures the failure is caught in doForward and triggers a retry,
            throw new RuntimeException("Failed to serialize MoContext to JSON", e);
        }
    }

    String buildFormBody(MoContext ctx) {
        StringBuilder sb = new StringBuilder();
        appendParam(sb, "from", ctx.from());
        appendParam(sb, "to", ctx.to());
        appendParam(sb, "text", ctx.text());
        appendParam(sb, "timestamp", ctx.timestamp());
        appendParam(sb, "ingateway", ctx.ingateway());
        appendParam(sb, "message_center", ctx.messageCenter());
        appendParam(sb, "coding", String.valueOf(ctx.dataCoding()));

        if (!sb.isEmpty()) {
            sb.setLength(sb.length() - 1);
        }
        return sb.toString();
    }

    private void appendParam(StringBuilder sb, String key, String value) {
        if (value != null && !value.isEmpty()) {
            sb.append(URLEncoder.encode(key, StandardCharsets.UTF_8));
            sb.append('=');
            sb.append(URLEncoder.encode(value, StandardCharsets.UTF_8));
            sb.append('&');
        }
    }

    private String urlEncode(String value) {
        return value != null ? URLEncoder.encode(value, StandardCharsets.UTF_8) : "";
    }

    private void handleFailure(String url, MoContext ctx, int attempt, String error, ForwardFormat format) {
        if (attempt >= MAX_RETRIES) {
            logger.error("MO forward failed completely after {} retries for from: {}. Last error: {}",
                    MAX_RETRIES, maskPhone(ctx.from()), error);
        } else {
            logger.warn("MO forward attempt {} failed for from: {}. Error: {}. Scheduling retry.",
                    attempt, maskPhone(ctx.from()), error);
            scheduleRetry(url, ctx, attempt, format);
        }
    }

    private void scheduleRetry(String url, MoContext ctx, int attempt, ForwardFormat format) {
        try {
            Thread.sleep(RETRY_INTERVAL_MS);
            doForward(url, ctx, attempt + 1, format);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("Retry sleep interrupted for from: {}", maskPhone(ctx.from()));
        }
    }

    private String maskPhone(String phone) {
        if (phone == null || phone.length() < 6) {
            return "***";
        }
        int maskLen = phone.length() - 5;
        return phone.substring(0, 3) + "*".repeat(maskLen) + phone.substring(phone.length() - 2);
    }

    public enum ForwardFormat {
        FORM,
        JSON
    }

    public record MoContext(
            String from,
            String to,
            String text,
            String timestamp,
            String ingateway,
            String messageCenter,
            byte dataCoding
    ) {
    }
}