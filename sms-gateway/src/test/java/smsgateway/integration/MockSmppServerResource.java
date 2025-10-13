package smsgateway.integration;

import com.cloudhopper.smpp.*;
import com.cloudhopper.smpp.impl.DefaultSmppServer;
import com.cloudhopper.smpp.impl.DefaultSmppSessionHandler;
import com.cloudhopper.smpp.pdu.*;
import com.cloudhopper.smpp.type.SmppProcessingException;
import io.netty.channel.nio.NioEventLoopGroup;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MockSmppServerResource implements QuarkusTestResourceLifecycleManager {

    private static final Logger logger = LoggerFactory.getLogger(MockSmppServerResource.class);
    public static final int PORT = 2778;
    public static final BlockingQueue<PduRequest<?>> receivedPdus = new LinkedBlockingQueue<>();

    private DefaultSmppServer smppServer;

    @Override
    public Map<String, String> start() {
        logger.info("Starting Mock SMPP Server on port {}", PORT);
        receivedPdus.clear();

        SmppServerConfiguration config = new SmppServerConfiguration();
        config.setPort(PORT);
        config.setSystemId("mock-server");
        config.setMaxConnectionSize(10);
        config.setDefaultRequestExpiryTimeout(30000);
        config.setDefaultWindowMonitorInterval(15000);
        config.setNonBlockingSocketsEnabled(true);

        smppServer =
                new DefaultSmppServer(
                        config,
                        new TestSmppServerHandler(),
                        Executors.newSingleThreadScheduledExecutor(),
                        new NioEventLoopGroup(1),
                        new NioEventLoopGroup(2));

        try {
            smppServer.start();
            logger.info("Mock SMPP Server started.");
        } catch (Exception e) {
            throw new RuntimeException("Could not start mock SMPP server", e);
        }

        return Collections.emptyMap();
    }

    @Override
    public void stop() {
        if (smppServer != null) {
            logger.info("Stopping Mock SMPP Server...");
            smppServer.destroy();
            logger.info("Mock SMPP Server stopped.");
        }
    }

    private static class TestSmppServerHandler implements SmppServerHandler {
        @Override
        public void sessionBindRequested(
                Long sessionId, SmppSessionConfiguration sessionConfiguration, BaseBind bindRequest)
                throws SmppProcessingException {
            logger.info(
                    "Mock Server: Accepting bind request from {}",
                    sessionConfiguration.getSystemId());
            // You could add credential checks here if needed
        }

        @Override
        public void sessionCreated(
                Long sessionId, SmppServerSession session, BaseBindResp preparedBindResponse)
                throws SmppProcessingException {
            logger.info(
                    "Mock Server: Session created for {}",
                    session.getConfiguration().getSystemId());
            session.serverReady(new TestSmppSessionHandler(session));
        }

        @Override
        public void sessionDestroyed(Long sessionId, SmppServerSession session) {
            logger.info(
                    "Mock Server: Session destroyed for {}",
                    session.getConfiguration().getSystemId());
        }
    }

    private static class TestSmppSessionHandler extends DefaultSmppSessionHandler {
        private final SmppSession session;

        public TestSmppSessionHandler(SmppSession session) {
            super(logger);
            this.session = session;
        }

        @Override
        public PduResponse firePduRequestReceived(PduRequest pduRequest) {
            logger.info("Mock Server: Received PDU: {}", pduRequest);
            receivedPdus.offer(pduRequest);

            if (pduRequest instanceof SubmitSm) {
                SubmitSm submitSm = (SubmitSm) pduRequest;
                SubmitSmResp resp = submitSm.createResponse();
                String messageId = "mock-smscid-" + UUID.randomUUID();
                resp.setMessageId(messageId);
                resp.setCommandStatus(SmppConstants.STATUS_OK);

                // Schedule DLR to be sent back
                scheduleDlr(submitSm, messageId);

                return resp;
            }
            return pduRequest.createResponse();
        }

        private void scheduleDlr(SubmitSm originalSubmitSm, String messageId) {
            Executors.newSingleThreadScheduledExecutor()
                    .schedule(
                            () -> {
                                try {
                                    DeliverSm dlr = new DeliverSm();
                                    dlr.setEsmClass(
                                            SmppConstants.ESM_CLASS_MT_SMSC_DELIVERY_RECEIPT);
                                    dlr.setSourceAddress(originalSubmitSm.getDestAddress());
                                    dlr.setDestAddress(originalSubmitSm.getSourceAddress());

                                    String dlrText =
                                            String.format(
                                                    "id:%s sub:001 dlvrd:001 submit date:2401011200 done date:2401011201 stat:DELIVRD err:000 text:Test",
                                                    messageId);
                                    dlr.setShortMessage(
                                            dlrText.getBytes(StandardCharsets.US_ASCII));

                                    logger.info(
                                            "Mock Server: Sending DLR for messageId {}", messageId);

                                    session.sendRequestPdu(dlr, 30000, false);
                                } catch (Exception e) {
                                    logger.error("Mock Server: Failed to send DLR", e);
                                }
                            },
                            1500,
                            TimeUnit.MILLISECONDS); // Send DLR after a short delay
        }
    }
}
