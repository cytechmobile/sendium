package smsgateway.smpp.server;

import com.cloudhopper.smpp.SmppConstants;
import com.cloudhopper.smpp.SmppSession;
import com.cloudhopper.smpp.impl.DefaultSmppSessionHandler;
import com.cloudhopper.smpp.pdu.EnquireLink;
import com.cloudhopper.smpp.pdu.PduRequest;
import com.cloudhopper.smpp.pdu.PduResponse;
import com.cloudhopper.smpp.pdu.SubmitSm;
import com.cloudhopper.smpp.pdu.SubmitSmResp;
import com.cloudhopper.smpp.type.RecoverablePduException;
import com.cloudhopper.smpp.type.UnrecoverablePduException;
import java.util.UUID;
import org.slf4j.Logger;
import smsgateway.dto.IncomingSms;
import smsgateway.providers.LogProvider;
import smsgateway.routing.router.SmsRouter;

public class SmppServerPduHandler extends DefaultSmppSessionHandler {
    private final Logger logger;

    private final SmsRouter smsRouter;
    private final SmppSession session;
    private final Long sessionId;

    public SmppServerPduHandler(SmsRouter smsRouter, SmppSession session, Long sessionId) {
        super(
                LogProvider.getSmppServerLogger(
                        session.getConfiguration().getLoggingOptions().getLoggerName()));
        this.smsRouter = smsRouter;
        this.session = session;
        this.sessionId = sessionId;
        this.logger =
                LogProvider.getSmppServerLogger(
                        session.getConfiguration().getLoggingOptions().getLoggerName());
    }

    @Override
    public PduResponse firePduRequestReceived(PduRequest pduRequest) {
        if (pduRequest instanceof SubmitSm submitSm) {
            String messageId = UUID.randomUUID().toString();
            logger.info(
                    "SubmitSm received from System ID: {}. Message ID: {}, Source: {}, Dest: {}, Text: '{}'",
                    session.getConfiguration().getSystemId(),
                    messageId,
                    submitSm.getSourceAddress(),
                    submitSm.getDestAddress(),
                    new String(submitSm.getShortMessage()));

            SubmitSmResp resp = submitSm.createResponse();
            resp.setMessageId(messageId);

            IncomingSms incomingSms =
                    new IncomingSms(
                            submitSm.getSourceAddress().getAddress(),
                            submitSm.getDestAddress().getAddress(),
                            new String(submitSm.getShortMessage()));
            incomingSms.setInternalId(messageId);
            if ((submitSm.getRegisteredDelivery()
                                    & SmppConstants.REGISTERED_DELIVERY_SMSC_RECEIPT_REQUESTED)
                            == SmppConstants.REGISTERED_DELIVERY_SMSC_RECEIPT_REQUESTED
                    || (submitSm.getRegisteredDelivery()
                                    & SmppConstants.REGISTERED_DELIVERY_SMSC_RECEIPT_ON_FAILURE)
                            == SmppConstants.REGISTERED_DELIVERY_SMSC_RECEIPT_ON_FAILURE
                    || (submitSm.getRegisteredDelivery()
                                    & SmppConstants.REGISTERED_DELIVERY_SMSC_RECEIPT_ON_SUCCESS)
                            == SmppConstants.REGISTERED_DELIVERY_SMSC_RECEIPT_ON_SUCCESS) {
                incomingSms.setSessionId(sessionId);
            }
            smsRouter.route(incomingSms);
            return resp;

        } else if (pduRequest instanceof EnquireLink el) {
            logger.info(
                    "Received EnquireLink from System ID: {}",
                    session.getConfiguration().getSystemId());
            var enquireLinkResp = el.createResponse();
            enquireLinkResp.setCommandStatus(SmppConstants.STATUS_OK);
            return enquireLinkResp;

        } else {
            logger.warn(
                    "Received unexpected PDU request from System ID: {}: {}",
                    session.getConfiguration().getSystemId(),
                    pduRequest.getName());
            PduResponse response = pduRequest.createResponse();
            response.setCommandStatus(SmppConstants.STATUS_INVCMDID);
            return response;
        }
    }

    public void fireExpectedPduResponseReceived(PduResponse pduResponse) {
        logger.debug("Expected PDU response received: {}", pduResponse);
    }

    public void fireUnexpectedPduResponseReceived(PduResponse pduResponse) {
        logger.warn("Unexpected PDU response received: {}", pduResponse);
    }

    public void fireUnrecoverablePduException(UnrecoverablePduException e) {
        logger.error("Unrecoverable PDU exception: {}", e.getMessage(), e);
    }

    public void fireRecoverablePduException(RecoverablePduException e) {
        logger.warn("Recoverable PDU exception: {}", e.getMessage(), e);
    }

    public void fireUnknownThrowable(Throwable t) {
        logger.error("Unknown throwable: {}", t.getMessage(), t);
    }

    public void fireChannelUnexpectedlyClosed() {
        logger.warn(
                "Channel unexpectedly closed for session: {}",
                session.getConfiguration().getSystemId());
    }

    public void firePduRequestExpired(PduRequest pduRequest) {
        logger.warn("PDU request expired: {}", pduRequest);
    }
}
