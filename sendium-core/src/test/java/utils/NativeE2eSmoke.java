package utils;

import com.cloudhopper.smpp.SmppBindType;
import com.cloudhopper.smpp.SmppConstants;
import com.cloudhopper.smpp.SmppServerConfiguration;
import com.cloudhopper.smpp.SmppServerHandler;
import com.cloudhopper.smpp.SmppServerSession;
import com.cloudhopper.smpp.SmppSession;
import com.cloudhopper.smpp.SmppSessionConfiguration;
import com.cloudhopper.smpp.SmppSessionHandler;
import com.cloudhopper.smpp.impl.DefaultSmppClient;
import com.cloudhopper.smpp.impl.DefaultSmppServer;
import com.cloudhopper.smpp.impl.DefaultSmppSessionHandler;
import com.cloudhopper.smpp.pdu.BaseBind;
import com.cloudhopper.smpp.pdu.BaseBindResp;
import com.cloudhopper.smpp.pdu.DeliverSm;
import com.cloudhopper.smpp.pdu.PduRequest;
import com.cloudhopper.smpp.pdu.PduResponse;
import com.cloudhopper.smpp.pdu.SubmitSm;
import com.cloudhopper.smpp.pdu.SubmitSmResp;
import com.cloudhopper.smpp.type.Address;
import com.cloudhopper.smpp.type.SmppChannelException;
import com.cloudhopper.smpp.type.SmppInvalidArgumentException;
import com.cloudhopper.smpp.type.SmppProcessingException;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.sun.net.httpserver.HttpServer;
import io.netty.channel.nio.NioEventLoopGroup;
import org.h2.mvstore.MVStore;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class NativeE2eSmoke {
    private static final String IMAGE = System.getenv().getOrDefault("SENDIUM_NATIVE_IMAGE", "sendium:native-e2e");
    private static final int SENDIUM_HTTP_PORT = 18080;
    private static final int SENDIUM_SMPP_PORT = 27777;
    private static final int UPSTREAM_SMPP_PORT = 27779;
    private static final Duration TIMEOUT = Duration.ofSeconds(90);

    public static void main(String[] args) throws Exception {
        String containerName = "sendium-native-e2e-" + UUID.randomUUID().toString().substring(0, 8);
        Process container = null;

        try (UpstreamSmppServer upstream = new UpstreamSmppServer(UPSTREAM_SMPP_PORT);
             CallbackServer callbackServer = new CallbackServer()) {
            upstream.start();
            callbackServer.start();

            Path workDir = Files.createTempDirectory("sendium-native-e2e-");
            writeRuntimeConfig(workDir);
            container = startSendiumContainer(containerName, workDir);
            waitForPort("localhost", SENDIUM_HTTP_PORT, TIMEOUT);
            waitForPort("localhost", SENDIUM_SMPP_PORT, TIMEOUT);
            require(upstream.awaitSessionBound(), "Sendium native container did not bind to the upstream SMPP server");
            Thread.sleep(3_000);

            container = verifyUnpushedDlrSurvivesRestart(containerName, workDir, upstream);
            verifySmppSubmitGetsDeliverSm(upstream, 2);
            verifyHttpSubmitGetsDlrCallback(upstream, callbackServer, 3);
        } catch (Throwable t) {
            printDockerLogs(containerName);
            throw t;
        } finally {
            stopContainer(containerName);
            if (container != null) {
                container.destroyForcibly();
            }
        }
    }

    private static void verifySmppSubmitGetsDeliverSm(UpstreamSmppServer upstream, int expectedSubmitCount) throws Exception {
        try (DownstreamSmppClient client = new DownstreamSmppClient()) {
            client.start();
            SubmitSmResp response = client.sendSms("smpp-sender", "306900000001", "native smpp e2e");
            require(response.getCommandStatus() == SmppConstants.STATUS_OK,
                    "SMPP submit_sm_resp status was " + response.getCommandStatus());
            require(response.getMessageId() != null && !response.getMessageId().isBlank(),
                    "SMPP submit_sm_resp did not contain a message id");
            require(upstream.awaitSubmitCount(expectedSubmitCount), "Upstream SMPP server did not receive the SMPP-originated message");
            DeliverSm deliverSm = client.awaitDeliverSm();
            require(deliverSm != null, "Downstream SMPP client did not receive deliver_sm");
            String body = new String(deliverSm.getShortMessage(), StandardCharsets.UTF_8);
            require(body.contains("DELIVRD"), "Downstream deliver_sm was not delivered: " + body);
            Thread.sleep(500);
        }
    }

    private static void verifyHttpSubmitGetsDlrCallback(UpstreamSmppServer upstream, CallbackServer callbackServer,
                                                        int expectedSubmitCount) throws Exception {
        String dlrUrl = "http://host.docker.internal:" + callbackServer.port() + "/dlr?status=%d&id=%s";
        String query = "username=http-user"
                + "&password=http-pass"
                + "&from=http-sender"
                + "&to=306900000002"
                + "&text=" + URLEncoder.encode("native http e2e", StandardCharsets.UTF_8)
                + "&dlr-url=" + URLEncoder.encode(dlrUrl, StandardCharsets.UTF_8);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + SENDIUM_HTTP_PORT + "/sendsms?" + query))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        require(response.statusCode() == HttpURLConnection.HTTP_ACCEPTED,
                "HTTP /sendsms returned " + response.statusCode() + ": " + response.body());

        String gatewayId = response.body().trim();
        require(!gatewayId.isBlank(), "HTTP /sendsms did not return a gateway message id");
        require(upstream.awaitSubmitCount(expectedSubmitCount), "Upstream SMPP server did not receive the HTTP-originated message");

        String callbackQuery = callbackServer.awaitCallback();
        require(callbackQuery != null, "DLR callback URL was not called");
        require(callbackQuery.contains("status=1"), "DLR callback did not contain delivered status: " + callbackQuery);
        require(callbackQuery.contains("id=" + gatewayId), "DLR callback did not contain gateway id " + gatewayId + ": " + callbackQuery);
    }

    private static Process verifyUnpushedDlrSurvivesRestart(String containerName, Path workDir,
                                                            UpstreamSmppServer upstream) throws Exception {
        upstream.setDeliveryReceiptDelayMillis(2_500);
        String gatewayId;
        try (DownstreamSmppClient client = new DownstreamSmppClient()) {
            client.start();
            SubmitSmResp response = client.sendSms("smpp-sender", "306900000003", "native restart dlr e2e");
            require(response.getCommandStatus() == SmppConstants.STATUS_OK,
                    "SMPP restart submit_sm_resp status was " + response.getCommandStatus());
            gatewayId = response.getMessageId();
            require(gatewayId != null && !gatewayId.isBlank(), "SMPP restart submit_sm_resp did not contain a message id");
            require(upstream.awaitSubmitCount(1), "Upstream SMPP server did not receive the restart test message");
        } finally {
            upstream.setDeliveryReceiptDelayMillis(1_000);
        }

        Thread.sleep(6_000);
        stopContainer(containerName);
        assertUnpushedDlrPersisted(workDir, gatewayId, "before restart");

        Process replayContainer = startSendiumContainer(containerName, workDir);
        waitForPort("localhost", SENDIUM_HTTP_PORT, TIMEOUT);
        waitForPort("localhost", SENDIUM_SMPP_PORT, TIMEOUT);
        require(upstream.awaitSessionBoundCount(2), "Sendium native container did not rebind to upstream after restart");

        try (DownstreamSmppClient reconnectedClient = new DownstreamSmppClient()) {
            reconnectedClient.start();
            DeliverSm deliverSm = reconnectedClient.awaitDeliverSm();
            require(deliverSm != null, "Reconnected downstream SMPP client did not receive persisted unpushed DLR");
            String body = new String(deliverSm.getShortMessage(), StandardCharsets.UTF_8);
            require(body.contains("DELIVRD"), "Persisted unpushed DLR was not delivered: " + body);
        }

        stopContainer(containerName);
        replayContainer.destroyForcibly();
        assertUnpushedDlrRemoved(workDir, gatewayId, "after replay");
        Process container = startSendiumContainer(containerName, workDir);
        waitForPort("localhost", SENDIUM_HTTP_PORT, TIMEOUT);
        waitForPort("localhost", SENDIUM_SMPP_PORT, TIMEOUT);
        require(upstream.awaitSessionBoundCount(3), "Sendium native container did not rebind to upstream after replay check");
        return container;
    }

    private static Process startSendiumContainer(String containerName, Path workDir) throws Exception {
        List<String> command = List.of(
                "docker", "run", "--rm", "-d",
                "--name", containerName,
                "--add-host", "host.docker.internal:host-gateway",
                "-p", SENDIUM_HTTP_PORT + ":8080",
                "-p", SENDIUM_SMPP_PORT + ":27777",
                "-v", workDir.resolve("conf").toAbsolutePath() + ":/work/conf",
                "-v", workDir.resolve("data").toAbsolutePath() + ":/work/data",
                "-v", workDir.resolve("logs").toAbsolutePath() + ":/work/logs",
                "-e", "QUARKUS_LOG_LEVEL=INFO",
                "-e", "LOG_LEVEL=INFO",
                IMAGE
        );
        Process process = run(command, true);
        require(process.waitFor(30, TimeUnit.SECONDS), "Timed out starting Sendium container");
        require(process.exitValue() == 0, "Failed to start Sendium container");
        return process;
    }

    private static void writeRuntimeConfig(Path workDir) throws IOException {
        Path conf = workDir.resolve("conf");
        Files.createDirectories(conf);
        Files.createDirectories(workDir.resolve("data"));
        Files.createDirectories(workDir.resolve("logs"));

        Files.writeString(conf.resolve("credentials.yml"), """
                credentials:
                  - type: SMPP
                    accountId: "smpp-e2e"
                    systemId: "smpp-user"
                    password: "smpp-pass"
                  - type: HTTP
                    accountId: "http-e2e"
                    systemId: "http-user"
                    password: "http-pass"
                """, StandardCharsets.UTF_8);

        Files.writeString(conf.resolve("smsg.properties"), """
                outSms.instance.route.enable = true
                outSms.instance.route.type = smppclient
                outSms.instance.route.username = upstream-user
                outSms.instance.route.password = upstream-pass
                outSms.instance.route.host = host.docker.internal
                outSms.instance.route.port = 27779
                outSms.instance.route.tps = 0
                outSms.instance.route.connections.transceivers = 1
                outSms.instance.route.connection.healthcheck = true
                outSms.instance.route.print.msgs = false

                outSms.instance.smpp.enable = true
                outSms.instance.smpp.type = smppserver
                outSms.instance.smpp.tps = 0
                outSms.instance.smpp.print.msgs = false
                outSms.instance.smpp.srv.host = 0.0.0.0
                outSms.instance.smpp.srv.port = 27777
                outSms.instance.smpp.srv.bindTimeout = 5000
                outSms.instance.smpp.srv.defaultRequestExpiryTimeout = 30000
                outSms.instance.smpp.srv.defaultWindowMonitorInterval = 15000
                outSms.instance.smpp.srv.defaultWindowSize = 1000
                outSms.instance.smpp.srv.maxConnections = 1000
                outSms.instance.smpp.srv.maxConnectionsPerIP = 4
                outSms.instance.smpp.conf.maxConnectionsPerUser.default = 4
                outSms.instance.smpp.conf.maxRate.default = 0
                """, StandardCharsets.UTF_8);

        Files.writeString(conf.resolve("routingTable.conf"), """
                [default]
                MESSAGE:type:==:0
                MESSAGE:type:==:11
                MESSAGE:type:==:14
                MESSAGE:type:==:17
                MESSAGE:type:==:10
                smppserver.smpp:type:==:18

                [MESSAGE]
                route::default:
                """, StandardCharsets.UTF_8);
    }

    private static void waitForPort(String host, int port, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            try (Socket ignored = new Socket(host, port)) {
                return;
            } catch (IOException ignored) {
                Thread.sleep(500);
            }
        }
        throw new IllegalStateException("Timed out waiting for " + host + ':' + port);
    }

    private static Process run(List<String> command, boolean inheritOutput) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command);
        if (inheritOutput) {
            builder.inheritIO();
        }
        return builder.start();
    }

    private static void printDockerLogs(String containerName) {
        try {
            run(List.of("docker", "logs", containerName), true).waitFor(30, TimeUnit.SECONDS);
        } catch (Exception ignored) {
        }
    }

    private static void stopContainer(String containerName) {
        try {
            run(List.of("docker", "stop", containerName), true).waitFor(30, TimeUnit.SECONDS);
        } catch (Exception ignored) {
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private static void assertUnpushedDlrPersisted(Path workDir, String gatewayId, String phase) {
        requireUnpushedDlrPresence(workDir, gatewayId, true, phase);
    }

    private static void assertUnpushedDlrRemoved(Path workDir, String gatewayId, String phase) {
        requireUnpushedDlrPresence(workDir, gatewayId, false, phase);
    }

    private static void requireUnpushedDlrPresence(Path workDir, String gatewayId, boolean expectedPresent, String phase) {
        Path dbPath = workDir.resolve("data").resolve("dlr-mvstore.db");
        require(Files.exists(dbPath), "DLR MVStore does not exist " + phase + ": " + dbPath);
        try (MVStore store = new MVStore.Builder().fileName(dbPath.toAbsolutePath().toString()).readOnly().open()) {
            Map<String, String> dlrStore = store.openMap("unpushedDlrStore");
            Map<String, String> dlrIndex = store.openMap("unpushedDlrIndex");
            boolean present = dlrStore.values().stream().anyMatch(value -> value.contains("\"serial\":\"" + gatewayId + "\""));
            require(present == expectedPresent,
                    "Unexpected unpushed DLR presence " + phase + " for gatewayId " + gatewayId
                            + ": " + present + " expected " + expectedPresent
                            + " storeSize=" + dlrStore.size() + " indexSize=" + dlrIndex.size());
            if (expectedPresent) {
                require(dlrIndex.containsKey("smpp-user"), "Unpushed DLR index did not contain smpp-user " + phase);
            }
        }
    }

    private static final class CallbackServer implements AutoCloseable {
        private final CountDownLatch latch = new CountDownLatch(1);
        private final List<String> queries = Collections.synchronizedList(new ArrayList<>());
        private final HttpServer server;

        private CallbackServer() throws IOException {
            server = HttpServer.create(new InetSocketAddress("0.0.0.0", 0), 0);
            server.createContext("/dlr", exchange -> {
                queries.add(exchange.getRequestURI().getRawQuery());
                byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
                latch.countDown();
            });
        }

        private void start() {
            server.start();
        }

        private int port() {
            return server.getAddress().getPort();
        }

        private String awaitCallback() throws InterruptedException {
            if (!latch.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                return null;
            }
            return queries.getFirst();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    private static final class UpstreamSmppServer implements AutoCloseable {
        private final int port;
        private final AtomicInteger receivedSubmits = new AtomicInteger();
        private final AtomicInteger boundSessions = new AtomicInteger();
        private final CountDownLatch sessionBound = new CountDownLatch(1);
        private final CountDownLatch firstTwoSubmits = new CountDownLatch(2);
        private final Set<SmppSession> sessions = Collections.newSetFromMap(new ConcurrentHashMap<>());
        private final DefaultSmppServer server;
        private volatile long deliveryReceiptDelayMillis = 1_000;

        private UpstreamSmppServer(int port) {
            this.port = port;
            SmppServerConfiguration configuration = new SmppServerConfiguration();
            configuration.setDefaultWindowSize(1000);
            configuration.setPort(port);

            SmppSessionHandler sessionHandler = new DefaultSmppSessionHandler() {
                @Override
                public PduResponse firePduRequestReceived(PduRequest pduRequest) {
                    if (!(pduRequest instanceof SubmitSm submitSm)) {
                        return pduRequest.createResponse();
                    }

                    String messageId = "e2e-" + receivedSubmits.incrementAndGet();
                    firstTwoSubmits.countDown();
                    SubmitSmResp response = (SubmitSmResp) pduRequest.createResponse();
                    response.setMessageId(messageId);
                    Thread.ofVirtual().start(() -> sendDeliveryReceipt(submitSm, messageId));
                    return response;
                }
            };

            SmppServerHandler bindHandler = new SmppServerHandler() {
                @Override
                public void sessionBindRequested(Long sessionId, SmppSessionConfiguration sessionConfiguration,
                                                 BaseBind bindRequest) throws SmppProcessingException {
                    sessionConfiguration.setName("upstream-e2e");
                }

                @Override
                public void sessionCreated(Long sessionId, SmppServerSession session,
                                            BaseBindResp preparedBindResponse) throws SmppProcessingException {
                    sessions.add(session);
                    boundSessions.incrementAndGet();
                    sessionBound.countDown();
                    session.serverReady(sessionHandler);
                }

                @Override
                public void sessionDestroyed(Long sessionId, SmppServerSession session) {
                    sessions.remove(session);
                }
            };

            server = new DefaultSmppServer(
                    configuration,
                    bindHandler,
                    null,
                    new NioEventLoopGroup(1, new ThreadFactoryBuilder().setNameFormat("upstream-boss-%d").setDaemon(true).build()),
                    new NioEventLoopGroup(2, new ThreadFactoryBuilder().setNameFormat("upstream-worker-%d").setDaemon(true).build())
            );
        }

        private void start() throws SmppChannelException {
            server.start();
        }

        private boolean awaitSubmitCount(int expected) throws InterruptedException {
            if (receivedSubmits.get() >= expected) {
                return true;
            }
            long deadline = System.nanoTime() + TIMEOUT.toNanos();
            while (System.nanoTime() < deadline) {
                if (receivedSubmits.get() >= expected) {
                    return true;
                }
                firstTwoSubmits.await(500, TimeUnit.MILLISECONDS);
            }
            return false;
        }

        private boolean awaitSessionBound() throws InterruptedException {
            return sessionBound.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        }

        private boolean awaitSessionBoundCount(int expected) throws InterruptedException {
            long deadline = System.nanoTime() + TIMEOUT.toNanos();
            while (System.nanoTime() < deadline) {
                if (boundSessions.get() >= expected) {
                    return true;
                }
                Thread.sleep(500);
            }
            return false;
        }

        private void setDeliveryReceiptDelayMillis(long deliveryReceiptDelayMillis) {
            this.deliveryReceiptDelayMillis = deliveryReceiptDelayMillis;
        }

        private void sendDeliveryReceipt(SubmitSm submitSm, String messageId) {
            try {
                Thread.sleep(deliveryReceiptDelayMillis);
                DeliverSm deliverSm = new DeliverSm();
                deliverSm.setDataCoding(SmppConstants.DATA_CODING_DEFAULT);
                deliverSm.setEsmClass(SmppConstants.ESM_CLASS_MT_SMSC_DELIVERY_RECEIPT);
                deliverSm.setSourceAddress(submitSm.getDestAddress());
                deliverSm.setDestAddress(submitSm.getSourceAddress());
                String dlr = "id:" + messageId
                        + " sub:001 dlvrd:001 submit date:2605191200 done date:2605191200 stat:DELIVRD err:000 text:e2e";
                deliverSm.setShortMessage(dlr.getBytes(StandardCharsets.UTF_8));
                for (SmppSession session : sessions) {
                    if (session.isBound() && session.getBindType() != SmppBindType.TRANSMITTER) {
                        session.sendRequestPdu(deliverSm, 30_000, true);
                        return;
                    }
                }
            } catch (Exception e) {
                throw new IllegalStateException("Failed to send upstream delivery receipt on port " + port, e);
            }
        }

        @Override
        public void close() {
            server.destroy(0, 100);
        }
    }

    private static final class DownstreamSmppClient implements AutoCloseable {
        private final CountDownLatch deliverSmLatch = new CountDownLatch(1);
        private final DefaultSmppClient client;
        private volatile DeliverSm deliverSm;
        private SmppSession session;

        private DownstreamSmppClient() {
            client = new DefaultSmppClient(new NioEventLoopGroup(
                    1,
                    new ThreadFactoryBuilder().setNameFormat("downstream-client-%d").setDaemon(true).build()
            ));
        }

        private void start() throws Exception {
            Exception lastFailure = null;
            int attempt = 0;
            long deadline = System.nanoTime() + TIMEOUT.toNanos();
            while (System.nanoTime() < deadline) {
                attempt++;
                try {
                    session = client.bind(createConfiguration(), createSessionHandler());
                    return;
                } catch (Exception e) {
                    lastFailure = e;
                    if (session != null) {
                        session.destroy();
                        session = null;
                    }
                    System.err.println("Downstream SMPP bind attempt " + attempt + " failed: "
                            + e.getClass().getSimpleName() + ": " + e.getMessage());
                    Thread.sleep(500);
                }
            }
            throw new IllegalStateException("Downstream SMPP client could not bind after " + attempt + " attempts", lastFailure);
        }

        private SmppSessionConfiguration createConfiguration() {
            SmppSessionConfiguration configuration = new SmppSessionConfiguration(SmppBindType.TRANSCEIVER, "smpp-user", "smpp-pass");
            configuration.setHost("localhost");
            configuration.setPort(SENDIUM_SMPP_PORT);
            configuration.setWindowSize(1000);
            return configuration;
        }

        private DefaultSmppSessionHandler createSessionHandler() {
            return new DefaultSmppSessionHandler() {
                @Override
                public PduResponse firePduRequestReceived(PduRequest pduRequest) {
                    if (pduRequest instanceof DeliverSm receivedDeliverSm) {
                        deliverSm = receivedDeliverSm;
                        deliverSmLatch.countDown();
                    }
                    return pduRequest.createResponse();
                }
            };
        }

        private SubmitSmResp sendSms(String from, String to, String text) throws SmppInvalidArgumentException, InterruptedException,
                com.cloudhopper.smpp.type.SmppTimeoutException, com.cloudhopper.smpp.type.SmppChannelException,
                com.cloudhopper.smpp.type.UnrecoverablePduException, com.cloudhopper.smpp.type.RecoverablePduException {
            SubmitSm submit = new SubmitSm();
            submit.setSourceAddress(new Address((byte) 0, (byte) 0, from));
            submit.setDestAddress(new Address((byte) 0, (byte) 0, to));
            submit.setRegisteredDelivery(SmppConstants.REGISTERED_DELIVERY_SMSC_RECEIPT_REQUESTED);
            submit.setDataCoding(SmppConstants.DATA_CODING_DEFAULT);
            submit.setShortMessage(text.getBytes(StandardCharsets.UTF_8));
            return session.submit(submit, 10_000);
        }

        private DeliverSm awaitDeliverSm() throws InterruptedException {
            if (!deliverSmLatch.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                return null;
            }
            return deliverSm;
        }

        @Override
        public void close() {
            if (session != null) {
                session.destroy();
            }
            client.destroy(0, 0);
        }
    }
}
