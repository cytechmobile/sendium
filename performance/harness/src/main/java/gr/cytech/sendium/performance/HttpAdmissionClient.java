package gr.cytech.sendium.performance;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import static gr.cytech.sendium.performance.MessageLedger.Admission.*;

/** Fixed-rate scheduling is external; submission never waits for an acknowledgement. */
public final class HttpAdmissionClient implements AutoCloseable {
    public enum Problem { INVALID_ACKNOWLEDGEMENT, UNEXPECTED_STATUS, AUTHENTICATION_FAILURE, TRANSPORT_FAILURE }
    public record Submission(String token, CompletableFuture<MessageLedger.Admission> completion) { }
    private final RunConfig config;
    private final HttpIngressSettings settings;
    private final MessageLedger ledger;
    private final HttpGatewayProtocol protocol;
    private final HttpClient client;
    private final Map<String, CompletableFuture<HttpResponse<String>>> pending = new ConcurrentHashMap<>();
    private final EnumMap<Problem, AtomicLong> problems = new EnumMap<>(Problem.class);
    private boolean closed;

    public HttpAdmissionClient(RunConfig config, HttpIngressSettings settings, MessageLedger ledger) {
        this(config, settings, ledger, HttpGatewayProtocol.SENDIUM);
    }

    public HttpAdmissionClient(RunConfig config, HttpIngressSettings settings, MessageLedger ledger,
            HttpGatewayProtocol protocol) {
        if (!"http".equals(config.ingressMode()) || config.dlrEnabled() && settings.callbackBase() == null) {
            throw new IllegalArgumentException("HTTP ingress requires callback settings when delivery reports are enabled");
        }
        this.config = config;
        this.settings = settings;
        this.ledger = ledger;
        this.protocol = java.util.Objects.requireNonNull(protocol);
        client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofMillis(config.admissionTimeoutMillis())).build();
        for (Problem problem : Problem.values()) { problems.put(problem, new AtomicLong()); }
    }

    public synchronized Optional<Submission> submit(boolean measured) {
        return submit(measured, System.nanoTime());
    }

    public synchronized Optional<Submission> submit(boolean measured, long scheduledNanos) {
        if (closed) { throw new IllegalStateException("HTTP client is closed"); }
        String token = ledger.attempt(measured, scheduledNanos);
        if (token == null) { return Optional.empty(); }
        try {
            HttpRequest request = HttpRequest.newBuilder(uri(token))
                    .timeout(Duration.ofMillis(config.admissionTimeoutMillis())).GET().build();
            ledger.markDispatched(token);
            var transport = client.sendAsync(request, HttpResponse.BodyHandlers.limiting(
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8), 4096));
            var future = transport.copy().orTimeout(config.admissionTimeoutMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            future.whenComplete((_, error) -> { if (error != null) { transport.cancel(true); } });
            pending.put(token, future);
            var completion = future.handle((response, error) -> {
                try {
                    MessageLedger.Admission outcome;
                    String gatewayId = null;
                    if (error != null) {
                        Throwable cause = error;
                        while ((cause instanceof java.util.concurrent.CompletionException
                                || cause instanceof java.util.concurrent.ExecutionException)
                                && cause.getCause() != null) { cause = cause.getCause(); }
                        outcome = cause instanceof HttpTimeoutException || cause instanceof java.util.concurrent.TimeoutException ? TIMED_OUT : FAILED;
                        if (outcome == FAILED) { problem(Problem.TRANSPORT_FAILURE); }
                    } else if (response.statusCode() == 202 && validAcknowledgement(response.body().strip())) {
                        outcome = ACCEPTED;
                        if (protocol == HttpGatewayProtocol.SENDIUM) { gatewayId = response.body().strip(); }
                    } else if (response.statusCode() == 202) {
                        outcome = FAILED;
                        problem(Problem.INVALID_ACKNOWLEDGEMENT);
                    } else if (response.statusCode() == 503 || response.statusCode() == 429) {
                        outcome = REJECTED;
                    } else if (response.statusCode() >= 400 && response.statusCode() < 500) {
                        outcome = REJECTED;
                        boolean notRoutable = protocol == HttpGatewayProtocol.KANNEL
                                && response.body().strip().equals("Not routable. Do not try again.");
                        problem(response.statusCode() == 401 || response.statusCode() == 403 && !notRoutable
                                ? Problem.AUTHENTICATION_FAILURE : Problem.UNEXPECTED_STATUS);
                    } else {
                        // A server/redirect error is not proof that the message was never admitted.
                        outcome = FAILED;
                        problem(Problem.UNEXPECTED_STATUS);
                    }
                    synchronized (ledger) {
                        if (ledger.isFrozen()) { return PENDING; }
                        ledger.admission(token, outcome, gatewayId);
                    }
                    return outcome;
                } finally {
                    pending.remove(token);
                }
            });
            return Optional.of(new Submission(token, completion));
        } catch (RuntimeException exception) {
            problem(Problem.TRANSPORT_FAILURE);
            ledger.admission(token, FAILED);
            return Optional.of(new Submission(token, CompletableFuture.completedFuture(FAILED)));
        }
    }

    private URI uri(String token) {
        String query = "username=" + encode(settings.username()) + "&password=" + encode(settings.password())
                + "&from=" + encode(settings.source()) + "&to=" + encode(settings.destination())
                + "&text=" + encode(token) + "&coding=0&charset=UTF-8";
        if (config.dlrEnabled()) {
            String callback = settings.callbackBase().toASCIIString() + "?token=" + encode(token)
                    + "&messageId=" + (protocol == HttpGatewayProtocol.KANNEL ? "%F" : "%s") + "&status=%d";
            query += "&dlr-url=" + encode(callback);
            if (protocol == HttpGatewayProtocol.KANNEL) { query += "&dlr-mask=1"; }
        }
        return URI.create(settings.admissionUri().toASCIIString() + "?" + query);
    }

    static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }

    private boolean validAcknowledgement(String body) {
        return protocol == HttpGatewayProtocol.SENDIUM ? canonicalUuid(body)
                : body.equals("0: Accepted for delivery") || body.equals("3: Queued for later delivery");
    }

    static boolean canonicalUuid(String value) {
        try { return UUID.fromString(value).toString().equals(value); }
        catch (IllegalArgumentException exception) { return false; }
    }

    private void problem(Problem problem) { if (!ledger.isFrozen()) { problems.get(problem).incrementAndGet(); } }

    public Map<Problem, Long> problems() {
        Map<Problem, Long> result = new EnumMap<>(Problem.class);
        problems.forEach((key, value) -> result.put(key, value.get()));
        return Map.copyOf(result);
    }

    public int pendingRequests() { return pending.size(); }

    @Override public synchronized void close() {
        if (closed) { return; }
        closed = true;
        pending.values().forEach(future -> future.cancel(true));
        client.shutdownNow();
    }
}
