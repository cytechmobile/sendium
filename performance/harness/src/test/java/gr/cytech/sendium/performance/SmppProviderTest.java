package gr.cytech.sendium.performance;

import com.cloudhopper.smpp.*;
import com.cloudhopper.smpp.impl.DefaultSmppClient;
import com.cloudhopper.smpp.impl.DefaultSmppSessionHandler;
import com.cloudhopper.smpp.pdu.*;
import com.cloudhopper.smpp.type.Address;
import io.netty.channel.nio.NioEventLoopGroup;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static gr.cytech.sendium.performance.MessageLedger.Admission.ACCEPTED;
import static gr.cytech.sendium.performance.MessageLedger.Event.RECEIPT_FORWARDED;

class SmppProviderTest {
    private RunConfig config(boolean dlr, int responseDelay, int receiptDelay) {
        return new RunConfig(1, "smpp", 100, 100, 0, 1, 1, dlr, responseDelay,
                receiptDelay, 3000, 10, 10);
    }

    private ProviderSettings settings(int pending) throws Exception {
        try (var socket = new java.net.ServerSocket(0)) {
            return new ProviderSettings("127.0.0.1", socket.getLocalPort(), "provider", "secret", 4, 10, pending, 3000);
        }
    }

    @Test
    void delayedResponsesAndSuccessfulReceiptsUseProviderIdAndReverseAddresses() throws Exception {
        RunConfig config = config(true, 100, 80);
        MessageLedger ledger = new MessageLedger(config, "test", System::nanoTime, TimeUnit.SECONDS.toNanos(5));
        String token = ledger.attempt(true);
        ledger.admission(token, ACCEPTED);
        CountDownLatch received = new CountDownLatch(1);
        AtomicReference<DeliverSm> delivered = new AtomicReference<>();
        try (SmppProvider provider = new SmppProvider(config, settings(10), ledger)) {
            provider.start();
            try (Client client = new Client(provider.port(), "secret", new DefaultSmppSessionHandler() {
                @Override public PduResponse firePduRequestReceived(PduRequest request) {
                    if (request instanceof DeliverSm receipt) {
                        delivered.set(receipt);
                        ledger.event(token, RECEIPT_FORWARDED);
                        received.countDown();
                    }
                    return request.createResponse();
                }
            })) {
                assertTrue(provider.awaitBound(Duration.ofSeconds(3)));
                long start = System.nanoTime();
                SubmitSmResp response = client.session.submit(submit(token, true), 3000);
                assertTrue(System.nanoTime() - start >= TimeUnit.MILLISECONDS.toNanos(90));
                assertEquals(SmppConstants.STATUS_OK, response.getCommandStatus());
                assertTrue(received.await(3, TimeUnit.SECONDS));
                assertTrue(new String(delivered.get().getShortMessage(), StandardCharsets.US_ASCII)
                        .contains("id:" + response.getMessageId() + " "));
                assertTrue(new String(delivered.get().getShortMessage(), StandardCharsets.US_ASCII).contains("stat:DELIVRD"));
                assertEquals("to", delivered.get().getSourceAddress().getAddress());
                assertEquals("from", delivered.get().getDestAddress().getAddress());
                assertEquals(SmppConstants.ESM_CLASS_MT_SMSC_DELIVERY_RECEIPT, delivered.get().getEsmClass());
                awaitIdle(provider);
                assertEquals(1, ledger.snapshot().acceptedReceiptForwarded());
                assertTrue(ledger.latencies().get(MessageLedger.Latency.SIMULATOR_RECEIPT_DELAY).minMillis() >= 70);
                assertEquals(0, ledger.snapshot().violations());
                assertTrue(provider.snapshot().problems().values().stream().allMatch(value -> value == 0));
            }
        }
    }

    @Test
    void reportsDisabledProduceUniqueResponseIdsAndNoReceipts() throws Exception {
        RunConfig config = config(false, 0, 0);
        MessageLedger ledger = new MessageLedger(config, "test", System::nanoTime, TimeUnit.SECONDS.toNanos(5));
        try (SmppProvider provider = new SmppProvider(config, settings(10), ledger)) {
            provider.start();
            try (Client client = new Client(provider.port(), "secret", new DefaultSmppSessionHandler())) {
                String first = ledger.attempt(true);
                String second = ledger.attempt(true);
                ledger.admission(first, ACCEPTED);
                ledger.admission(second, ACCEPTED);
                var firstResponse = client.session.submit(submit(first, false), 3000);
                var secondResponse = client.session.submit(submit(second, false), 3000);
                assertNotEquals(firstResponse.getMessageId(), secondResponse.getMessageId());
                awaitIdle(provider);
                assertEquals(2, ledger.snapshot().providerResponses());
                assertEquals(0, ledger.snapshot().receiptsGenerated());
                assertFalse(ledger.hasOutstandingWork());
            }
        }
    }

    @Test
    void malformedUnknownAndMultipartSubmissionsAreRejected() throws Exception {
        RunConfig config = config(false, 0, 0);
        MessageLedger ledger = new MessageLedger(config, "test", System::nanoTime, TimeUnit.SECONDS.toNanos(5));
        try (SmppProvider provider = new SmppProvider(config, settings(10), ledger)) {
            provider.start();
            try (Client client = new Client(provider.port(), "secret", new DefaultSmppSessionHandler())) {
                assertEquals(SmppConstants.STATUS_INVMSGID,
                        client.session.submit(submit("unknown:1", false), 3000).getCommandStatus());
                String token = ledger.attempt(true);
                SubmitSm multipart = submit(token, false);
                multipart.setEsmClass(SmppConstants.ESM_CLASS_UDHI_MASK);
                assertEquals(SmppConstants.STATUS_INVOPTPARAMVAL, client.session.submit(multipart, 3000).getCommandStatus());
                assertEquals(SmppConstants.STATUS_INVOPTPARAMVAL,
                        client.session.submit(submit(token, true), 3000).getCommandStatus());
                assertEquals(0, ledger.snapshot().providerResponses());
                assertEquals(2, provider.snapshot().problems().get(SmppProvider.Problem.INVALID_SUBMISSION));
                assertEquals(1, ledger.snapshot().violations());
            }
        }
    }

    @Test
    void saturationIsBoundedAndDoesNotBlockOtherRequestsOnNetworkThread() throws Exception {
        RunConfig config = config(false, 500, 0);
        MessageLedger ledger = new MessageLedger(config, "test", System::nanoTime, TimeUnit.SECONDS.toNanos(5));
        try (SmppProvider provider = new SmppProvider(config, settings(1), ledger)) {
            provider.start();
            try (Client client = new Client(provider.port(), "secret", new DefaultSmppSessionHandler())) {
                String first = ledger.attempt(true);
                String second = ledger.attempt(true);
                var firstFuture = client.session.sendRequestPdu(submit(first, false), 3000, true);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (provider.snapshot().pending() == 0 && System.nanoTime() < deadline) { Thread.sleep(1); }
                assertEquals(1, provider.snapshot().pending());
                SubmitSmResp response = client.session.submit(submit(second, false), 3000);
                assertEquals(SmppConstants.STATUS_THROTTLED, response.getCommandStatus());
                assertTrue(firstFuture.await(3000));
                assertEquals(SmppConstants.STATUS_OK, firstFuture.getResponse().getCommandStatus());
                awaitIdle(provider);
                assertEquals(1, provider.snapshot().peakPending());
                assertEquals(1, provider.snapshot().problems().get(SmppProvider.Problem.SATURATION));
            }
        }
    }

    @Test
    void rejectedReceiptAcknowledgementsRemainVisibleAsProviderFailures() throws Exception {
        RunConfig config = config(true, 0, 0);
        MessageLedger ledger = new MessageLedger(config, "test", System::nanoTime, TimeUnit.SECONDS.toNanos(5));
        String token = ledger.attempt(true);
        ledger.admission(token, ACCEPTED);
        try (SmppProvider provider = new SmppProvider(config, settings(1), ledger)) {
            provider.start();
            try (Client client = new Client(provider.port(), "secret", new DefaultSmppSessionHandler() {
                @Override public PduResponse firePduRequestReceived(PduRequest request) {
                    PduResponse response = request.createResponse();
                    response.setCommandStatus(SmppConstants.STATUS_SYSERR);
                    return response;
                }
            })) {
                assertEquals(SmppConstants.STATUS_OK, client.session.submit(submit(token, true), 3000).getCommandStatus());
                awaitIdle(provider);
                assertEquals(1, provider.snapshot().problems().get(SmppProvider.Problem.RECEIPT_REJECTED));
                assertEquals(1, ledger.snapshot().acceptedAwaitingReceipt());
            }
        }
    }

    @Test
    void closeCancelsDelayedWorkAndReleasesResourcesIdempotently() throws Exception {
        RunConfig config = config(false, 5000, 0);
        MessageLedger ledger = new MessageLedger(config, "test", System::nanoTime, 0);
        ProviderSettings settings = settings(1);
        try (SmppProvider provider = new SmppProvider(config, settings, ledger)) {
            provider.start();
            try (Client client = new Client(provider.port(), "secret", new DefaultSmppSessionHandler())) {
                client.session.sendRequestPdu(submit(ledger.attempt(true), false), 3000, true);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (provider.snapshot().pending() == 0 && System.nanoTime() < deadline) { Thread.sleep(1); }
                assertEquals(1, provider.snapshot().pending());
                provider.close();
                provider.close();
                assertEquals(0, provider.snapshot().pending());
                assertEquals(0, ledger.snapshot().providerResponses());
                assertThrows(IllegalStateException.class, provider::start);
            }
        }
        try (var socket = new java.net.ServerSocket(settings.port())) {
            assertEquals(settings.port(), socket.getLocalPort());
        }
    }

    @Test
    void invalidBindCredentialsAreRejectedAndRedacted() throws Exception {
        RunConfig config = config(false, 0, 0);
        MessageLedger ledger = new MessageLedger(config, "test", System::nanoTime, 0);
        assertFalse(settings(1).toString().contains("secret"));
        try (SmppProvider provider = new SmppProvider(config, settings(1), ledger)) {
            provider.start();
            assertThrows(com.cloudhopper.smpp.type.SmppBindException.class, () -> {
                try (Client ignored = new Client(provider.port(), "wrong", new DefaultSmppSessionHandler())) { }
            });
            assertEquals(0, provider.snapshot().pending());
        }
    }

    private static SubmitSm submit(String token, boolean dlr) throws Exception {
        SubmitSm submit = new SubmitSm();
        submit.setSourceAddress(new Address((byte) 0, (byte) 0, "from"));
        submit.setDestAddress(new Address((byte) 0, (byte) 0, "to"));
        submit.setDataCoding(SmppConstants.DATA_CODING_DEFAULT);
        submit.setRegisteredDelivery(dlr ? SmppConstants.REGISTERED_DELIVERY_SMSC_RECEIPT_REQUESTED : 0);
        submit.setShortMessage(token.getBytes(StandardCharsets.US_ASCII));
        return submit;
    }

    private static void awaitIdle(SmppProvider provider) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (provider.snapshot().pending() > 0 && System.nanoTime() < deadline) { Thread.sleep(5); }
        assertEquals(0, provider.snapshot().pending());
    }

    private static class Client implements AutoCloseable {
        final NioEventLoopGroup group = new NioEventLoopGroup(1);
        final DefaultSmppClient client = new DefaultSmppClient(group);
        final SmppSession session;
        Client(int port, String password, DefaultSmppSessionHandler handler) throws Exception {
            SmppSessionConfiguration config = new SmppSessionConfiguration(SmppBindType.TRANSCEIVER, "provider", password);
            config.setHost("127.0.0.1");
            config.setPort(port);
            config.setWindowSize(10);
            config.setBindTimeout(3000);
            config.getLoggingOptions().setLogPdu(false);
            config.getLoggingOptions().setLogBytes(false);
            try {
                session = client.bind(config, handler);
            } catch (Exception exception) {
                client.destroy(0, 100);
                group.shutdownGracefully(0, 100, TimeUnit.MILLISECONDS).syncUninterruptibly();
                throw exception;
            }
        }
        @Override public void close() {
            session.destroy();
            client.destroy(0, 100);
            group.shutdownGracefully(0, 100, TimeUnit.MILLISECONDS).syncUninterruptibly();
        }
    }
}
