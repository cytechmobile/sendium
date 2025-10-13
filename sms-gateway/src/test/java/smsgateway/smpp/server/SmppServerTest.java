package smsgateway.smpp.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.cloudhopper.commons.util.windowing.WindowFuture;
import com.cloudhopper.smpp.SmppBindType;
import com.cloudhopper.smpp.SmppConstants;
import com.cloudhopper.smpp.SmppSession;
import com.cloudhopper.smpp.SmppSessionConfiguration;
import com.cloudhopper.smpp.impl.DefaultSmppClient;
import com.cloudhopper.smpp.impl.DefaultSmppSessionHandler;
import com.cloudhopper.smpp.pdu.DeliverSm;
import com.cloudhopper.smpp.pdu.EnquireLink;
import com.cloudhopper.smpp.pdu.PduRequest;
import com.cloudhopper.smpp.pdu.PduResponse;
import com.cloudhopper.smpp.pdu.SubmitSm;
import com.cloudhopper.smpp.pdu.SubmitSmResp;
import com.cloudhopper.smpp.type.Address;
import com.cloudhopper.smpp.type.SmppInvalidArgumentException;
import io.netty.channel.nio.NioEventLoopGroup;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import smsgateway.auth.ApiKeyService;
import smsgateway.dto.IncomingSms;
import smsgateway.integration.MockSmppServerResource;
import smsgateway.routing.config.RoutingRule;
import smsgateway.routing.loader.RoutingRuleLoader;
import smsgateway.routing.router.SmsRouter;
import smsgateway.services.DlrForwardingService;
import smsgateway.services.DlrMappingService;
import smsgateway.smpp.DynamicVendorConfigWatcher;
import smsgateway.smpp.VendorConf;

@QuarkusTest
@QuarkusTestResource(MockSmppServerResource.class) // Use our mock server
public class SmppServerTest {
    private static final Logger logger = LoggerFactory.getLogger(SmppServerTest.class);

    @Inject SmppServerConfig serverConfig;

    @InjectSpy SmsRouter smsRouter;
    @InjectSpy DlrMappingService dlrMappingService;
    @InjectSpy DlrForwardingService dlrForwardingService;
    @Inject ApiKeyService apiKeyService;
    @Inject DynamicVendorConfigWatcher dynamicVendorConfigWatcher;
    @Inject RoutingRuleLoader routingRuleLoader;

    DefaultSmppClient smppClient;
    SmppSession clientSession;
    ScheduledExecutorService clientExecutor;
    NioEventLoopGroup clientGroup; // For Netty
    ClientSmppSessionHandler testSessionHandler; // Handler instance
    String originalConfigRulesPath;
    String originalConfigVendorsPath;

    @BeforeEach
    void setUp() throws Exception {
        MockSmppServerResource.receivedPdus.clear();
        if (!serverConfig.enabled()) {
            logger.warn(
                    "SMPP Server is disabled in the configuration. Test might not run correctly unless overridden for test profile.");
        }
        originalConfigVendorsPath = dynamicVendorConfigWatcher.getConfigFilePath();
        dynamicVendorConfigWatcher.resetConfigStateForTest("./target/vendors.json");
        var testVendor =
                new VendorConf(
                        "testVendor_" + UUID.randomUUID(),
                        true,
                        "localhost",
                        MockSmppServerResource.PORT,
                        "test1",
                        "tost2",
                        1,
                        60,
                        0,
                        "SMPP");
        dynamicVendorConfigWatcher.persist(Set.of(testVendor));

        originalConfigRulesPath = routingRuleLoader.getConfigRulesPath();
        routingRuleLoader.setConfigRulesPath("./target/rules.json");
        routingRuleLoader.persistRules(
                new HashMap<>(
                        Map.of(
                                RoutingRuleLoader.DEFAULT_RULE_GROUP_NAME,
                                new ArrayList<>(
                                        List.of(
                                                new RoutingRule(
                                                        "test",
                                                        null,
                                                        testVendor.getId(),
                                                        null))))));
        var keys = new ArrayList<>(apiKeyService.getAdminKeys().values().stream().toList());
        var smppKey = ApiKeyService.ApiKey.smpp("smppclient1", "password");
        keys.add(smppKey);
        apiKeyService.updateApiKeys(keys);
        clientExecutor =
                Executors.newScheduledThreadPool(
                        1, Thread.ofVirtual().name("testClientExecutor-", 1).factory());
        clientGroup =
                new NioEventLoopGroup(1, Thread.ofVirtual().name("testClientGroup-", 1).factory());
        smppClient = new DefaultSmppClient(clientGroup, clientExecutor);

        testSessionHandler = new ClientSmppSessionHandler(logger);

        SmppSessionConfiguration sessionConfig = new SmppSessionConfiguration();
        sessionConfig.setHost(
                serverConfig.host().equals("0.0.0.0") ? "127.0.0.1" : serverConfig.host());
        sessionConfig.setPort(serverConfig.port());
        sessionConfig.setSystemId(smppKey.systemId());
        sessionConfig.setPassword(smppKey.password());
        sessionConfig.setType(SmppBindType.TRANSCEIVER);
        sessionConfig.setWindowSize(10);
        sessionConfig.setConnectTimeout(10000);
        sessionConfig.setRequestExpiryTimeout(30000);
        sessionConfig.setWriteTimeout(10000);
        sessionConfig.setCountersEnabled(true); // Enable counters for session0.hasCounters()
        sessionConfig
                .getLoggingOptions()
                .setLogBytes(false); // Disable byte logging for cleaner test output

        logger.info(
                "Attempting to connect and bind client to {}:{} with System ID: {}",
                sessionConfig.getHost(),
                sessionConfig.getPort(),
                sessionConfig.getSystemId());
        // Pass the session handler to the bind method
        clientSession = smppClient.bind(sessionConfig, testSessionHandler);
        // logger.info("SMPP client bound successfully. Session ID: {}",
        // clientSession.getSessionId()); // getSessionId() might not exist
        logger.info("SMPP client bound successfully.");
    }

    @AfterEach
    void tearDown() {
        if (clientSession != null && clientSession.isBound()) {
            logger.info("Unbinding and closing client session.");
            clientSession.unbind(5000); // Graceful unbind with timeout
        } else if (clientSession != null) {
            logger.info("Client session not bound or already unbound, destroying.");
            clientSession.destroy();
        }

        if (smppClient != null) {
            logger.info("Destroying SMPP client.");
            smppClient.destroy();
        }
        if (clientExecutor != null) {
            clientExecutor.shutdownNow();
        }
        if (clientGroup != null) {
            clientGroup.shutdownGracefully().awaitUninterruptibly(10, TimeUnit.SECONDS);
        }
        dynamicVendorConfigWatcher.resetConfigStateForTest(originalConfigVendorsPath);
        dynamicVendorConfigWatcher.forceReloadConfig();
        routingRuleLoader.setConfigRulesPath(originalConfigRulesPath);
        routingRuleLoader.reloadRules();
        MockSmppServerResource.receivedPdus.clear();
    }

    @Test
    void testSubmitSmAndReceiveDlr() throws Exception {
        final CountDownLatch dlrLatch = new CountDownLatch(1);
        final AtomicReference<String> submitRespMessageId = new AtomicReference<>();
        final AtomicReference<DeliverSm> receivedDlrAtomicRef = new AtomicReference<>();

        testSessionHandler.setDlrLatch(dlrLatch);
        testSessionHandler.setReceivedDlr(receivedDlrAtomicRef);

        SubmitSm submitSm =
                createSubmitSm(
                        "TestSender",
                        "TestReceiver",
                        "Hello SMPP Server!",
                        (byte) 1 /* request DLR */);
        logger.info("Client sending SubmitSm: {}", submitSm);

        SubmitSmResp submitResp = clientSession.submit(submitSm, 30000);

        logger.info("Client received SubmitSmResp: {}", submitResp);
        assertEquals(
                SmppConstants.STATUS_OK,
                submitResp.getCommandStatus(),
                "SubmitSm response status should be OK");
        assertNotNull(submitResp.getMessageId(), "SubmitSm response should have a message ID");
        assertTrue(
                submitResp.getMessageId().length() > 0,
                "SubmitSm response message ID should not be empty");
        submitRespMessageId.set(submitResp.getMessageId());

        logger.info("Waiting for DLR...");
        assertTrue(
                dlrLatch.await(10, TimeUnit.SECONDS), "DLR should be received within 10 seconds");

        DeliverSm dlr = receivedDlrAtomicRef.get();
        assertNotNull(dlr, "DLR PDU should not be null");

        String dlrText = new String(dlr.getShortMessage());
        logger.info("Asserting DLR content. DLR Text: {}", dlrText);

        assertTrue(
                dlrText.contains("id:" + submitRespMessageId.get()),
                "DLR text should contain the correct message ID");
        assertThat(dlrText).contains("stat:");

        assertEquals(
                "TestReceiver",
                dlr.getSourceAddress().getAddress(),
                "DLR source address should be original destination");
        assertEquals(
                "TestSender",
                dlr.getDestAddress().getAddress(),
                "DLR destination address should be original sender");
    }

    @Test
    void testConnectivity() throws Exception {
        logger.info("Testing basic connectivity with EnquireLink...");
        EnquireLink el = new EnquireLink();
        WindowFuture<Integer, PduRequest, PduResponse> future =
                clientSession.sendRequestPdu(el, 10000, true); // Send and don't wait
        assertNotNull(future, "Future for EnquireLink should not be null");
        assertTrue(
                future.await(10000L),
                "Timed out waiting for EnquireLink response"); // Use await(long)
        assertTrue(future.isSuccess(), "EnquireLink PDU request failed: " + future.getCause());
        PduResponse response = future.getResponse();
        assertNotNull(response, "Response to EnquireLink should not be null");
        assertEquals(
                SmppConstants.STATUS_OK,
                response.getCommandStatus(),
                "EnquireLink response status should be OK");
        logger.info("EnquireLink successful.");
    }

    @Test
    void testSubmitSmAndVerifyRouting() throws Exception {
        String testSender = "JavaClient";
        String testReceiver = "SmppServer";
        String testMessage = "Hello Router!";

        // Create SubmitSm - DLR request set to 0 (no DLR needed for this test)
        SubmitSm submitSm = createSubmitSm(testSender, testReceiver, testMessage, (byte) 0x00);
        logger.info("Client sending SubmitSm for routing test: {}", submitSm);

        // Send SubmitSm and get response
        SubmitSmResp submitResp = clientSession.submit(submitSm, 30000);

        logger.info("Client received SubmitSmResp for routing test: {}", submitResp);
        assertEquals(
                SmppConstants.STATUS_OK,
                submitResp.getCommandStatus(),
                "SubmitSm response status should be OK for routing test");
        assertNotNull(
                submitResp.getMessageId(),
                "SubmitSm response should have a message ID for routing test");
        assertTrue(
                submitResp.getMessageId().length() > 0,
                "SubmitSm response message ID should not be empty for routing test");

        // Verify that smsRouter.route() was called
        ArgumentCaptor<IncomingSms> incomingSmsCaptor = ArgumentCaptor.forClass(IncomingSms.class);
        verify(smsRouter, times(1)).route(incomingSmsCaptor.capture());

        // Assert the details of the captured IncomingSms object
        IncomingSms routedSms = incomingSmsCaptor.getValue();
        assertNotNull(routedSms, "Captured IncomingSms should not be null");
        assertEquals(testSender, routedSms.getFrom(), "Routed SMS source address mismatch");
        assertEquals(testReceiver, routedSms.getTo(), "Routed SMS destination address mismatch");
        assertEquals(testMessage, routedSms.getText(), "Routed SMS text mismatch");

        logger.info(
                "SmsRouter.route() verified successfully for SubmitSm: {}",
                submitSm.getSequenceNumber());
    }

    private SubmitSm createSubmitSm(
            String sourceAddr, String destAddr, String text, byte registeredDelivery)
            throws SmppInvalidArgumentException {
        SubmitSm submitSm = new SubmitSm();
        submitSm.setSourceAddress(
                new Address((byte) 0x01, (byte) 0x01, sourceAddr)); // Example TON/NPI
        submitSm.setDestAddress(new Address((byte) 0x01, (byte) 0x01, destAddr)); // Example TON/NPI
        submitSm.setShortMessage(text.getBytes());
        submitSm.setRegisteredDelivery(registeredDelivery);
        submitSm.setDataCoding(SmppConstants.DATA_CODING_DEFAULT);
        submitSm.setEsmClass((byte) 0x00);
        return submitSm;
    }

    // Inner class for session handling in tests
    public static class ClientSmppSessionHandler extends DefaultSmppSessionHandler {
        private CountDownLatch dlrLatch;
        private AtomicReference<DeliverSm> receivedDlr;

        public ClientSmppSessionHandler(Logger logger) {
            super(logger);
        }

        public void setDlrLatch(CountDownLatch dlrLatch) {
            this.dlrLatch = dlrLatch;
        }

        public void setReceivedDlr(AtomicReference<DeliverSm> receivedDlr) {
            this.receivedDlr = receivedDlr;
        }

        @Override
        public PduResponse firePduRequestReceived(PduRequest pduRequest) {
            logger.info("ClientSmppSessionHandler received PDU: {}", pduRequest);
            if (pduRequest instanceof DeliverSm) {
                DeliverSm dlr = (DeliverSm) pduRequest;
                if (dlr.getEsmClass() == SmppConstants.ESM_CLASS_MT_SMSC_DELIVERY_RECEIPT) {
                    logger.info("DLR content in handler: {}", new String(dlr.getShortMessage()));
                    if (this.receivedDlr != null) {
                        this.receivedDlr.set(dlr);
                    }
                    if (this.dlrLatch != null) {
                        this.dlrLatch.countDown();
                    }
                } else {
                    logger.info(
                            "Received DeliverSm in ClientSmppSessionHandler but not a DLR: {}",
                            pduRequest);
                }
            }
            return pduRequest.createResponse(); // Default behavior: respond to requests
        }

        @Override
        public void firePduRequestExpired(PduRequest pduRequest) {
            logger.warn("PDU request expired: {}", pduRequest);
        }
    }
}
