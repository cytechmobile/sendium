package smsgateway.smpp;

import com.cloudhopper.smpp.PduAsyncResponse;
import com.cloudhopper.smpp.SmppConstants;
import com.cloudhopper.smpp.SmppSession;
import com.cloudhopper.smpp.SmppSessionListener;
import com.cloudhopper.smpp.pdu.DeliverSm;
import com.cloudhopper.smpp.pdu.GenericNack;
import com.cloudhopper.smpp.pdu.PartialPdu;
import com.cloudhopper.smpp.pdu.Pdu;
import com.cloudhopper.smpp.pdu.PduRequest;
import com.cloudhopper.smpp.pdu.PduResponse;
import com.cloudhopper.smpp.pdu.SubmitSm;
import com.cloudhopper.smpp.pdu.SubmitSmResp;
import com.cloudhopper.smpp.type.RecoverablePduException;
import com.cloudhopper.smpp.type.UnrecoverablePduException;
import com.cloudhopper.smpp.util.SmppUtil;
import com.google.common.base.Strings;
import java.nio.channels.ClosedChannelException;
import org.slf4j.Logger;
import smsgateway.dto.IncomingSms;
import smsgateway.providers.LogProvider;
import smsgateway.services.DlrMappingService;

public class SmppClientSessionHandler implements SmppSessionListener {
    protected final SmppClientWorker smppClientWorker;
    protected final Logger logger;
    protected int consecutiveFailedEnquireLinks;
    protected long lastPduTimestamp;
    protected final DlrMappingService dlrMappingService;

    protected SmppSession session;

    public SmppClientSessionHandler(
            SmppClientWorker smppClientWorker, DlrMappingService dlrMappingService) {
        this.smppClientWorker = smppClientWorker;
        this.logger = LogProvider.getSmppClientLogger(smppClientWorker.getVendor().getId());
        this.consecutiveFailedEnquireLinks = 0;
        this.dlrMappingService = dlrMappingService;
    }

    public SmppSession getSession() {
        return session;
    }

    public void setSession(SmppSession session) {
        this.session = session;
    }

    /**
     * Called when a request PDU such as a "DeliverSM" has been received on a session. This method
     * provides a simply way to return a response PDU. If a non-null response PDU is returned, this
     * pdu will be sent back on the session's channel. If the response PDU is null, then no response
     * will be sent back and its up to the implementation to send back the response instead.
     *
     * @param pduRequest The request PDU received on this session
     * @return The response PDU to send back OR null if no response should be returned.
     */
    public PduResponse firePduRequestReceived(PduRequest pduRequest) {
        pduRequest.setReferenceObject(this);
        PduResponse resp;
        switch (pduRequest.getCommandId()) {
            case SmppConstants.CMD_ID_DELIVER_SM:
                DeliverSm deliverSm = (DeliverSm) pduRequest;
                // check if this is a dlr or an mo:
                if (SmppUtil.isMessageTypeAnyDeliveryReceipt(deliverSm.getEsmClass())) {
                    resp = smppClientWorker.parseDlrAndCreateResponse(deliverSm);
                } else {
                    resp = smppClientWorker.parseMoAndCreateResponse(deliverSm);
                }
                resp.setReferenceObject(this);
                logger.debug("responding to request:{} with resp:{}", pduRequest, resp);
                break;
            case SmppConstants.CMD_ID_SUBMIT_SM:
                // smsc sent sms to us!?! treating this another way of receiving an mo too
                SubmitSm submitSm = (SubmitSm) pduRequest;
                resp = smppClientWorker.parseMoAndCreateResponse(submitSm);
                logger.debug("responding to request:{} with resp:{}", pduRequest, resp);
                break;
            case SmppConstants.CMD_ID_ENQUIRE_LINK:
                resp = pduRequest.createResponse();
                resp.setReferenceObject(this);
                logger.trace(
                        "responding to enquire link request:{} with resp:{}", pduRequest, resp);
                break;
            case SmppConstants.CMD_ID_UNBIND:
                resp = pduRequest.createResponse();
                resp.setReferenceObject(this);
                logger.info("responding to unbind request:{} with resp:{}", pduRequest, resp);
                break;
            default:
                logger.debug(
                        "{}: received (invalid/not handled) pdu request: {}", this, pduRequest);
                resp = createGenericNack(pduRequest, SmppConstants.STATUS_INVCMDID);
                break;
        }

        return resp;
    }

    /**
     * Called when a request PDU has not received an associated response within the expiry time.
     * Usually, this means the request should be retried.
     *
     * @param pduRequest The request PDU received on this session
     */
    public void firePduRequestExpired(PduRequest pduRequest) {
        logger.info("{}: received expired request PDU: {}", this, pduRequest);

        switch (pduRequest.getCommandId()) {
            case SmppConstants.CMD_ID_SUBMIT_SM:
                // if I have saved a message object as a reference object
                // then just re-enqueue the msg
                if (pduRequest.getReferenceObject() != null) {
                    IncomingSms msg = (IncomingSms) pduRequest.getReferenceObject();
                    smppClientWorker.handleResponse(
                            this, SmppConstants.STATUS_DELIVERYFAILURE, null, msg);

                } else {
                    logger.warn(
                            "Pdu response with no object reference received, do nothing: {}",
                            pduRequest);
                    return;
                }
                break;
            case SmppConstants.CMD_ID_ENQUIRE_LINK:
                // we have an expired enquire link, we need to re-connect
                handleFailedEnquireLink();
                break;
            default:
                // is it OK to just ignore all other expired requests?
        }
    }

    /**
     * Called when the underlying channel of a session has been closed and it wasn't at the request
     * of our side. This will either indicate the remote system closed the socket OR the connection
     * dropped in-between. If the session's actual "close" method was called, this won't be
     * triggered.
     */
    public void fireChannelUnexpectedlyClosed() {
        logger.warn("{}: a channel closed unexpectedly", smppClientWorker.getFullName());
        smppClientWorker.removeConnection(this, false, true);
    }

    /**
     * Called when a response PDU is received for a previously sent request PDU. Only "expected"
     * responses are passed to this method. An "expected" response is a response that matches a
     * previously sent request. Both the original request and the response along with other info is
     * passed to this method.
     *
     * <p>NOTE: If another thread is "waiting" for a response, that thread will receive it vs. this
     * method. This method will only receive expected responses that were either sent
     * "asynchronously" or received after the originating thread timed out while waiting for a
     * response.
     *
     * @param pduAsyncResponse The "expected" response PDU received on this session
     */
    public void fireExpectedPduResponseReceived(PduAsyncResponse pduAsyncResponse) {
        logger.trace(
                "{}: received expected response PDU: {}", this, pduAsyncResponse.getResponse());

        /**
         * Its possible the response PDU really isn't the correct PDU we were waiting for, so we
         * should verify it. For example it is possible that a "Generic_Nack" could be returned by
         * the remote endpoint in response to a PDU.
         */
        switch (pduAsyncResponse.getRequest().getCommandId()) {
            case SmppConstants.CMD_ID_SUBMIT_SM:
                SubmitSm submit = (SubmitSm) pduAsyncResponse.getRequest();
                SubmitSmResp resp = (SubmitSmResp) pduAsyncResponse.getResponse();
                int statusCode = resp.getCommandStatus();
                IncomingSms msg = (IncomingSms) submit.getReferenceObject();
                // ask worker to handle response
                String respMessageId = resp.getMessageId();
                if (msg != null
                        && msg.getInternalId() != null
                        && respMessageId != null
                        && !respMessageId.isEmpty()) {
                    dlrMappingService.put(msg.getInternalId(), respMessageId);
                    logger.info(
                            "Stored mapping for internalId: {} to vendorMessageId: {}",
                            msg.getInternalId(),
                            respMessageId);
                }
                if (msg == null) {
                    logger.warn(
                            "{} no attached message for submit: {} with response:{}",
                            this,
                            submit,
                            resp);
                    return;
                }
                smppClientWorker.handleResponse(this, statusCode, respMessageId, msg);
                break;
            case SmppConstants.CMD_ID_ENQUIRE_LINK:
                // We are expecting EnquireLinkResp here
                if (pduAsyncResponse.getResponse().getCommandStatus() == SmppConstants.STATUS_OK) {
                    handleSuccessEnquireLink();
                } else {
                    // Any other status in the EnquireLinkResp is considered a failure for the
                    // keep-alive
                    logger.warn(
                            "Received non-OK status for EnquireLinkResp: {}",
                            pduAsyncResponse.getResponse().getCommandStatus());
                    handleFailedEnquireLink();
                }
                break;
            case SmppConstants.CMD_ID_BIND_TRANSCEIVER:
            case SmppConstants.CMD_ID_BIND_RECEIVER:
            case SmppConstants.CMD_ID_BIND_TRANSMITTER:
            default:
                break;
        }
    }

    /**
     * Called when a response PDU is received for a request this session never sent. Only
     * "unexpected" responses are passed to this method. An "unexpected" response is a response that
     * does NOT match a previously sent request. That can either happen because it really is an
     * invalid response OR another thread that originated the request "cancelled" it. Cancelling is
     * VERY uncommon so an invalid response is more likely.
     *
     * @param pduResponse The "unexpected" response PDU received on this session
     */
    public void fireUnexpectedPduResponseReceived(PduResponse pduResponse) {
        logger.warn(
                "{}: received unexpected response PDU (it will be ignored): {}", this, pduResponse);
    }

    /**
     * Called when an "unrecoverable" exception has been thrown downstream in the session's
     * pipeline. The best example is a PDU that has an impossible sequence number. The recommended
     * action is almost always to close the session and attempt to rebind at a later time.
     *
     * @param e The exception
     */
    public void fireUnrecoverablePduException(UnrecoverablePduException e) {
        logger.warn("{}: destroyed because of {}", this, e);
        smppClientWorker.removeConnection(this, true, true);
    }

    /**
     * Called when a "recoverable" exception has been thrown downstream in the session's pipeline.
     * The best example is a PDU that may have been missing some fields such as NULL byte. A
     * "recoverable" exception always includes a "PartialPdu" which always contains enough
     * information to create a "NACK" back. That's the recommended behavior of implementations -- to
     * trigger a GenericNack for PduRequests.
     */
    public void fireRecoverablePduException(RecoverablePduException e) {
        logger.warn("{}: received: {}", this, e);

        PartialPdu partialPdu = (PartialPdu) e.getPartialPdu();
        if (partialPdu.getReferenceObject() != null
                && partialPdu.getReferenceObject() instanceof IncomingSms) {
            IncomingSms msg = (IncomingSms) partialPdu.getReferenceObject();
            int commandStatus = partialPdu.getCommandStatus();
            if (commandStatus == SmppConstants.STATUS_OK) {
                commandStatus = SmppConstants.STATUS_UNKNOWNERR;
            }
            smppClientWorker.handleResponse(this, commandStatus, null, msg);
            return;
        }

        if (partialPdu.isRequest()) {
            partialPdu.setReferenceObject(this);
            GenericNack resp = partialPdu.createResponse();
            try {
                this.session.sendResponsePdu(resp);
            } catch (Exception ex) {
                logger.warn(
                        "received further exception while trying to send back generic nack for request:{}",
                        partialPdu,
                        ex);
            }
        }
    }

    /**
     * Called when any exception/throwable has been thrown downstream in the session's pipeline that
     * wasn't of the types: UnrecoverablePduException or RecoverablePduException.
     */
    public void fireUnknownThrowable(Throwable t) {
        if (t != null) {
            if (t instanceof ClosedChannelException) {
                fireChannelUnexpectedlyClosed();
            } else {
                logger.warn("{}: received Unknown Throwable: {}", this, t);
            }
        }
    }

    /**
     * Lookup a "command_status" value and returns a String that represents a result message
     * (description) of what the value means. A way to add helpful debugging information into log
     * files or management interfaces. This value is printed out via the toString() method for a PDU
     * response.
     *
     * @param commandStatus The command_status field to lookup
     * @return A String representing a short description of what the command_status value
     *     represents. For example, a command_status of 0 usually means "OK"
     */
    public String lookupResultMessage(int commandStatus) {
        String resp = SmppConstants.STATUS_MESSAGE_MAP.get(commandStatus);
        if (Strings.isNullOrEmpty(resp)) {
            resp = "UNKNOWN ERROR";
        }
        return resp;
    }

    /**
     * Lookup a name for the tag of a TLV and returns a String that represents a name for it. A way
     * to add helpful debugging information into logfiles or management interfaces.
     *
     * @param tag The TLV's tag value to lookup
     * @return A String representing a short name of what the tag value represents. For example, a
     *     tag of 0x001E usually means "receipted_message_id"
     */
    public String lookupTlvTagName(short tag) {
        return SmppConstants.TAG_NAME_MAP.get(tag);
    }

    /**
     * Called when ANY PDU received from connection. This method could be used to sniff all incoming
     * SMPP packet traffic, and also permit/deny it from further processing. Could be used for
     * advanced packet logging, counters and filtering.
     *
     * @param pdu the pdu
     * @return boolean allow PDU processing. If false PDU would be discarded.
     */
    public boolean firePduReceived(Pdu pdu) {
        lastPduTimestamp = System.currentTimeMillis();

        pdu.setReferenceObject(this);
        // Accept pdu for processing up chain
        logger.trace("{}: firePduReceived {} ", this, pdu);
        return true;
    }

    /**
     * Called when ANY PDU dispatched from connection. This method could be used to sniff all
     * outgoing SMPP packet traffic, and also permit/deny it from sending. Could be used for
     * advanced packet logging, counters and filtering.
     *
     * @param pdu the pdu
     * @return boolean allow PDU sending. If false PDU would be discarded.
     */
    public boolean firePduDispatch(Pdu pdu) {
        logger.trace("{}: firePduDispatch {} ", this, pdu);
        return true;
    }

    public void handleSuccessEnquireLink() {
        this.consecutiveFailedEnquireLinks = 0;
    }

    public static GenericNack createGenericNack(PduRequest<?> req, int status) {
        if (req == null) {
            throw new IllegalArgumentException("cannot create generic nack for null request");
        }

        GenericNack nack = req.createGenericNack(status);
        nack.setReferenceObject(req.getReferenceObject());
        return nack;
    }

    public void handleFailedEnquireLink() {
        this.consecutiveFailedEnquireLinks++;
        int maxErrors = 5;
        if (this.consecutiveFailedEnquireLinks >= maxErrors) {
            logger.warn(
                    "removing connection:{} due to reaching maximum consecutive number of failed enquire links: {}",
                    this,
                    maxErrors);
            smppClientWorker.removeConnection(this, true, true);
        }
    }
}
