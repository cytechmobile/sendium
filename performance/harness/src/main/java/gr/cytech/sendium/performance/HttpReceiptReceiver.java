package gr.cytech.sendium.performance;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded callback processing; saturation is exposed even when caller-runs backpressure succeeds. */
public final class HttpReceiptReceiver implements AutoCloseable {
    public enum Problem { SATURATION, LIMITED_HISTORY, INVALID_CALLBACK, UNEXPECTED_STATUS, CORRELATION_FAILURE, IO_FAILURE }
    private final HttpServer server;
    private final ThreadPoolExecutor executor;
    private final MessageLedger ledger;
    private final boolean dlrEnabled;
    private final EnumMap<Problem, AtomicLong> problems = new EnumMap<>(Problem.class);

    public HttpReceiptReceiver(InetSocketAddress address, String path, int threads, int queueCapacity,
            boolean dlrEnabled, MessageLedger ledger) throws IOException {
        if (threads < 1 || queueCapacity < 1 || path == null || !path.startsWith("/") || path.contains("?")) {
            throw new IllegalArgumentException("Invalid HTTP receiver settings");
        }
        this.ledger = ledger;
        this.dlrEnabled = dlrEnabled;
        for (Problem problem : Problem.values()) { problems.put(problem, new AtomicLong()); }
        server = HttpServer.create(address, queueCapacity);
        executor = new ThreadPoolExecutor(threads, threads, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity), Thread.ofPlatform().name("receipt-http-", 0).factory(),
                (task, pool) -> {
                    if (!pool.isShutdown()) { problem(Problem.SATURATION); task.run(); }
                });
        server.setExecutor(executor);
        server.createContext(path, exchange -> handle(exchange, path));
    }

    public void start() { server.start(); }
    public int port() { return server.getAddress().getPort(); }

    private void handle(HttpExchange exchange, String path) {
        try (exchange) {
            if (!"GET".equals(exchange.getRequestMethod()) || !path.equals(exchange.getRequestURI().getPath())) {
                problem(Problem.INVALID_CALLBACK);
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            Map<String, String> query;
            try {
                query = decodeQuery(exchange.getRequestURI().getRawQuery());
                if (!query.keySet().equals(java.util.Set.of("token", "messageId", "status"))
                        || !HttpAdmissionClient.canonicalUuid(query.get("messageId"))) {
                    throw new IllegalArgumentException();
                }
            } catch (IllegalArgumentException exception) {
                problem(Problem.INVALID_CALLBACK);
                exchange.sendResponseHeaders(400, -1);
                return;
            }
            if (!dlrEnabled || !"1".equals(query.get("status"))) {
                problem(Problem.UNEXPECTED_STATUS);
                exchange.sendResponseHeaders(422, -1);
                return;
            }
            MessageLedger.ReceiptResult result = ledger.receiveReceipt(query.get("token"), query.get("messageId"));
            if (result == MessageLedger.ReceiptResult.BEYOND_RETENTION) {
                problem(Problem.LIMITED_HISTORY);
            } else if (result == MessageLedger.ReceiptResult.INVALID) {
                problem(Problem.CORRELATION_FAILURE);
                exchange.sendResponseHeaders(400, -1);
                return;
            }
            // Duplicates are acknowledged idempotently; the ledger still flags the scenario violation.
            exchange.sendResponseHeaders(204, -1);
        } catch (IOException exception) {
            problem(Problem.IO_FAILURE);
        }
    }

    static Map<String, String> decodeQuery(String raw) {
        if (raw == null || raw.length() > 4096) { throw new IllegalArgumentException("Invalid query"); }
        Map<String, String> result = new HashMap<>();
        for (String field : raw.split("&", -1)) {
            String[] parts = field.split("=", 2);
            if (parts.length != 2) { throw new IllegalArgumentException("Invalid query field"); }
            String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            String value = URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
            if (value.isBlank() || result.putIfAbsent(key, value) != null) {
                throw new IllegalArgumentException("Duplicate or empty query field");
            }
        }
        return result;
    }

    private void problem(Problem problem) { problems.get(problem).incrementAndGet(); }
    public Map<Problem, Long> problems() {
        Map<Problem, Long> result = new EnumMap<>(Problem.class);
        problems.forEach((key, value) -> result.put(key, value.get()));
        return Map.copyOf(result);
    }

    @Override public void close() {
        server.stop(0);
        executor.shutdownNow();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
