package gr.cytech.sendium.performance;

import com.cloudhopper.smpp.*;
import com.cloudhopper.smpp.impl.DefaultSmppServer;
import com.cloudhopper.smpp.impl.DefaultSmppSessionHandler;
import com.cloudhopper.smpp.pdu.*;
import com.cloudhopper.smpp.type.*;
import io.netty.channel.nio.NioEventLoopGroup;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static gr.cytech.sendium.performance.MessageLedger.Admission.*;
import static gr.cytech.sendium.performance.MessageLedger.Event.*;

class SmppAdmissionClientTest {
    private RunConfig config(boolean dlr, int timeout, int capacity) {
        return new RunConfig(1, "smpp", 100, 100, 0, 1, 1, dlr, 0, 30, timeout, capacity, 10);
    }
    private MessageLedger ledger(RunConfig config) {
        return new MessageLedger(config, "smpp-test", System::nanoTime, TimeUnit.SECONDS.toNanos(5));
    }
    private SmppIngressSettings settings(int port) {
        return new SmppIngressSettings("127.0.0.1", port, "user", "secret", "from", "to", 10, 3000);
    }

    @Test
    void realProviderAndClientExchangeMessagesWithReportsEnabledAndDisabled() throws Exception {
        for (boolean dlr : new boolean[]{true, false}) {
            RunConfig config = config(dlr, 3000, 10);
            MessageLedger ledger = ledger(config);
            int port = freePort();
            try (SmppProvider provider = new SmppProvider(config,
                    new ProviderSettings("127.0.0.1", port, "user", "secret", 1, 10, 10, 3000), ledger);
                    SmppAdmissionClient client = new SmppAdmissionClient(config, settings(port), ledger)) {
                provider.start();
                client.connect();
                assertTrue(client.isBound());
                assertEquals(ACCEPTED, client.submit(true).orElseThrow().completion().get(5, TimeUnit.SECONDS));
                await(() -> !ledger.hasOutstandingWork());
                assertEquals(1, ledger.snapshot().acceptedProviderCompleted());
                assertEquals(dlr ? 1 : 0, ledger.snapshot().acceptedReceiptForwarded());
                assertEquals(0, ledger.snapshot().violations());
                assertTrue(client.problems().values().stream().allMatch(value -> value == 0));
            }
        }
    }

    @Test
    void earlyReceiptsPreserveTheirArrivalTimeAndDuplicatesAreNotDoubleCounted() throws Exception {
        RunConfig config = config(true, 3000, 10);
        MessageLedger ledger = ledger(config);
        CountDownLatch releaseAck = new CountDownLatch(1);
        try (Gateway gateway = new Gateway((server, request) -> {
            String token = new String(((SubmitSm) request).getShortMessage(), StandardCharsets.US_ASCII);
            ledger.event(token, PROVIDER_ARRIVAL);
            ledger.event(token, PROVIDER_RESPONSE);
            ledger.event(token, RECEIPT_GENERATED);
            server.tasks.submit(() -> {
                try {
                    assertEquals(SmppConstants.STATUS_OK, server.receipt("gateway-1", true));
                    assertEquals(SmppConstants.STATUS_OK, server.receipt("gateway-1", true));
                    releaseAck.await(3, TimeUnit.SECONDS);
                    server.session.get().sendResponsePdu(response(request, SmppConstants.STATUS_OK, "gateway-1"));
                } catch (Throwable failure) { server.failure.set(failure); }
            });
            return null;
        }); SmppAdmissionClient client = new SmppAdmissionClient(config, settings(gateway.port), ledger)) {
            client.connect();
            var submission = client.submit(true).orElseThrow();
            await(() -> client.bufferedReceipts() == 2);
            Thread.sleep(100);
            releaseAck.countDown();
            assertEquals(ACCEPTED, submission.completion().get(5, TimeUnit.SECONDS));
            assertNull(gateway.failure.get());
            assertEquals(1, ledger.snapshot().receiptsForwarded());
            assertEquals(1, ledger.snapshot().duplicateReceipts());
            assertEquals(0, client.bufferedReceipts());
            var latencies = ledger.latencies();
            assertTrue(latencies.get(MessageLedger.Latency.RECEIPT_FORWARDING).p99Millis()
                    < latencies.get(MessageLedger.Latency.ACKNOWLEDGEMENT).p50Millis());
        } finally { releaseAck.countDown(); }
    }

    @Test
    void negativeResponsesAndMissingIdsHaveDistinctOutcomes() throws Exception {
        for (int status : new int[]{SmppConstants.STATUS_OK, SmppConstants.STATUS_THROTTLED,
                SmppConstants.STATUS_MSGQFUL, SmppConstants.STATUS_SYSERR}) {
            RunConfig config = config(false, 3000, 10);
            MessageLedger ledger = ledger(config);
            try (Gateway gateway = new Gateway((server, request) -> response(request, status, null));
                    SmppAdmissionClient client = new SmppAdmissionClient(config, settings(gateway.port), ledger)) {
                client.connect();
                assertEquals(status == SmppConstants.STATUS_OK ? FAILED : REJECTED,
                        client.submit(true).orElseThrow().completion().get(5, TimeUnit.SECONDS));
                assertEquals(status == SmppConstants.STATUS_OK || status == SmppConstants.STATUS_SYSERR ? 1 : 0,
                        client.problems().values().stream().mapToLong(Long::longValue).sum());
            }
        }
    }

    @Test
    void requestBoundsAndTimeoutsDoNotLeakTheSmppWindow() throws Exception {
        RunConfig config = config(false, 200, 1);
        MessageLedger ledger = ledger(config);
        CountDownLatch received = new CountDownLatch(1);
        AtomicInteger submits = new AtomicInteger();
        try (Gateway gateway = new Gateway((server, request) -> {
            if (submits.incrementAndGet() == 1) { received.countDown(); return null; }
            return response(request, SmppConstants.STATUS_OK, "gateway-2");
        }); SmppAdmissionClient client = new SmppAdmissionClient(config, new SmppIngressSettings(
                "127.0.0.1", gateway.port, "user", "secret", "from", "to", 1, 3000), ledger)) {
            client.connect();
            var first = client.submit(true).orElseThrow();
            assertTrue(received.await(3, TimeUnit.SECONDS));
            assertTrue(client.submit(true).isEmpty());
            assertEquals(TIMED_OUT, first.completion().get(3, TimeUnit.SECONDS));
            assertEquals(ACCEPTED, client.submit(true).orElseThrow().completion().get(3, TimeUnit.SECONDS));
            assertEquals(1, ledger.snapshot().missed());
            assertEquals(1, ledger.snapshot().timedOut());
        }
    }

    @Test
    void unmatchedReceiptBufferIsBoundedAndMalformedReceiptsAreRejected() throws Exception {
        RunConfig config = config(true, 3000, 10);
        MessageLedger ledger = ledger(config);
        try (Gateway gateway = new Gateway((server, request) -> response(request, SmppConstants.STATUS_OK, "gateway"));
                SmppAdmissionClient client = new SmppAdmissionClient(config, settings(gateway.port), ledger)) {
            client.connect();
            for (int i = 0; i < 10; i++) { assertEquals(SmppConstants.STATUS_OK, gateway.receipt("unknown-" + i, true)); }
            assertEquals(10, client.bufferedReceipts());
            assertEquals(SmppConstants.STATUS_THROTTLED, gateway.receipt("overflow", true));
            assertEquals(SmppConstants.STATUS_INVOPTPARAMVAL, gateway.receipt("bad", false));
            client.finalizeReceipts();
            assertEquals(0, client.bufferedReceipts());
            assertEquals(10, client.problems().get(SmppAdmissionClient.Problem.UNCORRELATED_RECEIPT));
            assertEquals(1, client.problems().get(SmppAdmissionClient.Problem.SATURATION));
            assertEquals(1, client.problems().get(SmppAdmissionClient.Problem.INVALID_RECEIPT));
        }
    }

    @Test
    void receiptWithoutMappingAfterAdmissionTimeoutIsAmbiguousRatherThanProvenInvalid() throws Exception {
        RunConfig config = config(true, 200, 1);
        MessageLedger ledger = ledger(config);
        try (Gateway gateway = new Gateway((server, request) -> null);
                SmppAdmissionClient client = new SmppAdmissionClient(config, settings(gateway.port), ledger)) {
            client.connect();
            assertEquals(TIMED_OUT, client.submit(false).orElseThrow().completion().get(3, TimeUnit.SECONDS));
            assertEquals(SmppConstants.STATUS_OK, gateway.receipt("unacknowledged-gateway-id", true));
            client.finalizeReceipts();
            assertEquals(1, client.problems().get(SmppAdmissionClient.Problem.AMBIGUOUS_RECEIPT));
            assertEquals(0, client.problems().get(SmppAdmissionClient.Problem.UNCORRELATED_RECEIPT));
            assertEquals(0, ledger.snapshot().violations());
            assertEquals(0, ledger.snapshot().receiptsForwarded());
        }
    }

    @Test
    void receiptsBeyondRetainedGatewayMappingReportLimitedHistory() throws Exception {
        RunConfig config = config(true, 3000, 10);
        MessageLedger ledger = new MessageLedger(config, "smpp-test", System::nanoTime, 0);
        try (Gateway gateway = new Gateway((server, request) -> response(request, SmppConstants.STATUS_OK, "gateway"));
                SmppAdmissionClient client = new SmppAdmissionClient(config, settings(gateway.port), ledger)) {
            client.connect();
            var submission = client.submit(true).orElseThrow();
            assertEquals(ACCEPTED, submission.completion().get(3, TimeUnit.SECONDS));
            ledger.event(submission.token(), PROVIDER_ARRIVAL);
            ledger.event(submission.token(), PROVIDER_RESPONSE);
            ledger.event(submission.token(), RECEIPT_GENERATED);
            assertEquals(SmppConstants.STATUS_OK, gateway.receipt("gateway", true));
            assertEquals(SmppConstants.STATUS_OK, gateway.receipt("gateway", true));
            client.finalizeReceipts();
            assertEquals(1, client.problems().get(SmppAdmissionClient.Problem.LIMITED_HISTORY));
            assertEquals(0, client.problems().get(SmppAdmissionClient.Problem.UNCORRELATED_RECEIPT));
            assertEquals(1, ledger.snapshot().receiptsForwarded());
        }
    }

    @Test
    void disabledReceiptsAndInvalidCredentialsAreRejected() throws Exception {
        RunConfig config = config(false, 3000, 10);
        MessageLedger ledger = ledger(config);
        try (Gateway gateway = new Gateway((server, request) -> response(request, SmppConstants.STATUS_OK, "gateway"));
                SmppAdmissionClient client = new SmppAdmissionClient(config, settings(gateway.port), ledger)) {
            client.connect();
            assertEquals(SmppConstants.STATUS_INVOPTPARAMVAL, gateway.receipt("gateway", true));
            SmppIngressSettings bad = new SmppIngressSettings("127.0.0.1", gateway.port, "user", "wrong",
                    "from", "to", 10, 3000);
            try (SmppAdmissionClient rejected = new SmppAdmissionClient(config, bad, ledger)) {
                assertThrows(SmppBindException.class, rejected::connect);
            }
            assertFalse(settings(gateway.port).toString().contains("secret"));
        }
    }

    @Test
    void closingAfterWindowFreezePreservesPendingAdmissionAndDoesNotInventTransportErrors() throws Exception {
        RunConfig config = config(false, 3000, 1);
        MessageLedger ledger = ledger(config);
        CountDownLatch received = new CountDownLatch(1);
        try (Gateway gateway = new Gateway((server, request) -> { received.countDown(); return null; });
                SmppAdmissionClient client = new SmppAdmissionClient(config, settings(gateway.port), ledger)) {
            client.connect();
            var submission = client.submit(true).orElseThrow();
            assertTrue(received.await(3, TimeUnit.SECONDS));
            ledger.freeze(); client.close();
            assertEquals(PENDING, submission.completion().get(3, TimeUnit.SECONDS));
            assertEquals(1, ledger.snapshot().pendingAdmission());
            assertEquals(0, ledger.snapshot().failed());
            assertEquals(0, client.problems().get(SmppAdmissionClient.Problem.TRANSPORT_FAILURE));
        }
    }

    @Test
    void closeResolvesPendingAdmissionAndReleasesNetworkResources() throws Exception {
        RunConfig config = config(false, 3000, 1);
        MessageLedger ledger = ledger(config);
        CountDownLatch received = new CountDownLatch(1);
        try (Gateway gateway = new Gateway((server, request) -> { received.countDown(); return null; });
                SmppAdmissionClient client = new SmppAdmissionClient(config, settings(gateway.port), ledger)) {
            client.connect();
            var submission = client.submit(true).orElseThrow();
            assertTrue(received.await(3, TimeUnit.SECONDS));
            client.close();
            client.close();
            assertEquals(FAILED, submission.completion().get(3, TimeUnit.SECONDS));
            assertEquals(0, client.pendingRequests());
            assertEquals(0, ledger.snapshot().pendingAdmission());
            assertThrows(IllegalStateException.class, () -> client.submit(true));
        }
    }

    @Test
    void callbacksPipelineTheWholeWindowAndCorrelateReverseOrderResponses() throws Exception {
        RunConfig config = config(false, 3000, 10);
        MessageLedger ledger = ledger(config);
        var requests = new java.util.concurrent.CopyOnWriteArrayList<PduRequest>();
        CountDownLatch received = new CountDownLatch(10);
        try (Gateway gateway = new Gateway((server, request) -> {
            String token = new String(((SubmitSm) request).getShortMessage(), StandardCharsets.US_ASCII);
            ledger.event(token, PROVIDER_ARRIVAL);
            ledger.event(token, PROVIDER_RESPONSE);
            requests.add(request);
            received.countDown();
            return null;
        }); SmppAdmissionClient client = new SmppAdmissionClient(config, settings(gateway.port), ledger)) {
            client.connect();
            var submissions = new java.util.ArrayList<SmppAdmissionClient.Submission>();
            for (int i = 0; i < 10; i++) { submissions.add(client.submit(true).orElseThrow()); }
            assertTrue(received.await(2, TimeUnit.SECONDS), "All requests must be dispatched without waiting for replies");
            assertEquals(10, client.pendingRequests());
            assertTrue(client.submit(true).isEmpty());
            for (int i = requests.size() - 1; i >= 0; i--) {
                gateway.session.get().sendResponsePdu(response(requests.get(i), SmppConstants.STATUS_OK, "id-" + i));
            }
            for (var submission : submissions) {
                assertEquals(ACCEPTED, submission.completion().get(2, TimeUnit.SECONDS));
            }
            assertEquals(0, client.pendingRequests());
            assertEquals(10, ledger.snapshot().acceptedProviderCompleted());
            assertEquals(0, ledger.snapshot().violations());
        }
    }

    @Test
    void lateResponseAfterTimeoutDoesNotReadmitTheMessageOrOccupyTheWindow() throws Exception {
        RunConfig config = config(false, 200, 1);
        MessageLedger ledger = ledger(config);
        AtomicReference<PduRequest> firstRequest = new AtomicReference<>();
        try (Gateway gateway = new Gateway((server, request) -> {
            if (firstRequest.compareAndSet(null, request)) { return null; }
            return response(request, SmppConstants.STATUS_OK, "next-id");
        }); SmppAdmissionClient client = new SmppAdmissionClient(config, new SmppIngressSettings(
                "127.0.0.1", gateway.port, "user", "secret", "from", "to", 1, 3000), ledger)) {
            client.connect();
            var first = client.submit(true).orElseThrow();
            await(() -> firstRequest.get() != null);
            assertEquals(TIMED_OUT, first.completion().get(2, TimeUnit.SECONDS));
            gateway.session.get().sendResponsePdu(response(firstRequest.get(), SmppConstants.STATUS_OK, "late-id"));
            assertEquals(ACCEPTED, client.submit(true).orElseThrow().completion().get(2, TimeUnit.SECONDS));
            assertEquals(TIMED_OUT, first.completion().get());
            assertEquals(1, ledger.snapshot().timedOut());
            assertEquals(1, ledger.snapshot().accepted());
            assertEquals(0, ledger.snapshot().violations());
            assertEquals(0, client.pendingRequests());
        }
    }

    @Test
    void peerDisconnectCompletesAllAdmissionsWithoutWaitingForTheirTimeout() throws Exception {
        RunConfig config = config(false, 10000, 10);
        MessageLedger ledger = ledger(config);
        CountDownLatch received = new CountDownLatch(3);
        try (Gateway gateway = new Gateway((server, request) -> { received.countDown(); return null; });
                SmppAdmissionClient client = new SmppAdmissionClient(config, settings(gateway.port), ledger)) {
            client.connect();
            var submissions = new java.util.ArrayList<SmppAdmissionClient.Submission>();
            for (int i = 0; i < 3; i++) { submissions.add(client.submit(true).orElseThrow()); }
            assertTrue(received.await(2, TimeUnit.SECONDS));
            gateway.session.get().destroy();
            for (var submission : submissions) {
                assertEquals(FAILED, submission.completion().get(2, TimeUnit.SECONDS));
            }
            assertEquals(0, client.pendingRequests());
            assertEquals(3, ledger.snapshot().failed());
            assertEquals(0, ledger.snapshot().pendingAdmission());
            assertThrows(IllegalStateException.class, () -> client.submit(true));
        }
    }

    @Test
    void genericNackCompletesAdmissionAsInvalidAcknowledgement() throws Exception {
        RunConfig config = config(false, 3000, 10);
        MessageLedger ledger = ledger(config);
        try (Gateway gateway = new Gateway((server, request) -> {
            GenericNack response = new GenericNack();
            response.setSequenceNumber(request.getSequenceNumber());
            response.setCommandStatus(SmppConstants.STATUS_SYSERR);
            return response;
        }); SmppAdmissionClient client = new SmppAdmissionClient(config, settings(gateway.port), ledger)) {
            client.connect();
            assertEquals(FAILED, client.submit(true).orElseThrow().completion().get(2, TimeUnit.SECONDS));
            assertEquals(1, client.problems().get(SmppAdmissionClient.Problem.INVALID_ACKNOWLEDGEMENT));
            assertEquals(0, client.pendingRequests());
            assertEquals(1, ledger.snapshot().failed());
        }
    }

    @Test
    void stalledDispatcherDoesNotBlockSubmissionAndExpiredQueuedRequestsAreNeverSent() throws Exception {
        RunConfig config = config(false, 200, 2);
        MessageLedger ledger = ledger(config);
        CountDownLatch releaseDispatcher = new CountDownLatch(1);
        AtomicInteger received = new AtomicInteger();
        ThreadFactory factory = task -> Thread.ofPlatform().unstarted(() -> {
            try { releaseDispatcher.await(); task.run(); }
            catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
        });
        try (Gateway gateway = new Gateway((server, request) -> {
            received.incrementAndGet();
            return response(request, SmppConstants.STATUS_OK, "live-id");
        }); SmppAdmissionClient client = new SmppAdmissionClient(config, settings(gateway.port), ledger, factory)) {
            client.connect();
            var first = client.submit(true).orElseThrow();
            var second = client.submit(true).orElseThrow();
            assertEquals(TIMED_OUT, first.completion().get(2, TimeUnit.SECONDS));
            assertEquals(TIMED_OUT, second.completion().get(2, TimeUnit.SECONDS));
            assertEquals(0, received.get());
            assertEquals(0, client.pendingRequests());
            releaseDispatcher.countDown();
            assertEquals(ACCEPTED, client.submit(true).orElseThrow().completion().get(2, TimeUnit.SECONDS));
            assertEquals(1, received.get(), "Expired queued sends must not be transmitted when the dispatcher resumes");
            assertEquals(2, ledger.snapshot().timedOut());
            assertEquals(1, ledger.snapshot().accepted());
            assertEquals(0, ledger.snapshot().violations());
        } finally { releaseDispatcher.countDown(); }
    }

    private static SubmitSmResp response(PduRequest request, int status, String id) {
        SubmitSmResp response = (SubmitSmResp) request.createResponse();
        response.setCommandStatus(status);
        response.setMessageId(id);
        return response;
    }
    private static int freePort() throws Exception {
        try (var socket = new java.net.ServerSocket(0)) { return socket.getLocalPort(); }
    }
    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) { Thread.sleep(5); }
        assertTrue(condition.getAsBoolean());
    }
    private interface Handler { PduResponse handle(Gateway gateway, PduRequest request); }
    private static class Gateway implements AutoCloseable {
        final int port = freePort();
        final AtomicReference<SmppServerSession> session = new AtomicReference<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final ExecutorService tasks = Executors.newVirtualThreadPerTaskExecutor();
        final NioEventLoopGroup boss = new NioEventLoopGroup(1), worker = new NioEventLoopGroup(1);
        final DefaultSmppServer server;
        Gateway(Handler handler) throws Exception {
            SmppServerConfiguration config = new SmppServerConfiguration();
            config.setHost("127.0.0.1"); config.setPort(port); config.setDefaultWindowSize(10);
            server = new DefaultSmppServer(config, new SmppServerHandler() {
                @Override public void sessionBindRequested(Long id, SmppSessionConfiguration configuration, BaseBind request)
                        throws SmppProcessingException {
                    if (!"secret".equals(request.getPassword())) { throw new SmppProcessingException(SmppConstants.STATUS_INVPASWD); }
                    configuration.getLoggingOptions().setLogPdu(false);
                    configuration.getLoggingOptions().setLogBytes(false);
                }
                @Override public void sessionCreated(Long id, SmppServerSession created, BaseBindResp response) {
                    session.set(created);
                    created.serverReady(new DefaultSmppSessionHandler() {
                        @Override public PduResponse firePduRequestReceived(PduRequest request) {
                            return request instanceof SubmitSm ? handler.handle(Gateway.this, request) : request.createResponse();
                        }
                    });
                }
                @Override public void sessionDestroyed(Long id, SmppServerSession destroyed) { }
            }, null, boss, worker);
            server.start();
        }
        int receipt(String id, boolean delivered) throws Exception {
            DeliverSm receipt = new DeliverSm();
            receipt.setEsmClass(SmppConstants.ESM_CLASS_MT_SMSC_DELIVERY_RECEIPT);
            receipt.setSourceAddress(new Address((byte) 0, (byte) 0, "to"));
            receipt.setDestAddress(new Address((byte) 0, (byte) 0, "from"));
            receipt.setShortMessage(("id:" + id + " sub:001 dlvrd:001 submit date:2610141200 done date:2610141200 stat:"
                    + (delivered ? "DELIVRD" : "UNDELIV") + " err:000 text:DLR").getBytes(StandardCharsets.US_ASCII));
            var future = session.get().sendRequestPdu(receipt, 3000, true);
            assertTrue(future.await(3000));
            return future.getResponse().getCommandStatus();
        }
        @Override public void close() {
            tasks.shutdownNow();
            server.destroy(0, 100);
            boss.shutdownGracefully(0, 100, TimeUnit.MILLISECONDS).syncUninterruptibly();
            worker.shutdownGracefully(0, 100, TimeUnit.MILLISECONDS).syncUninterruptibly();
        }
    }
}
