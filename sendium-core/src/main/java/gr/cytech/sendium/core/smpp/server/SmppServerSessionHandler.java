package gr.cytech.sendium.core.smpp.server;

import com.cloudhopper.commons.util.HexUtil;
import com.cloudhopper.smpp.PduAsyncResponse;
import com.cloudhopper.smpp.SmppConstants;
import com.cloudhopper.smpp.SmppSession;
import com.cloudhopper.smpp.pdu.GenericNack;
import com.cloudhopper.smpp.pdu.PartialPdu;
import com.cloudhopper.smpp.pdu.Pdu;
import com.cloudhopper.smpp.pdu.PduRequest;
import com.cloudhopper.smpp.pdu.PduResponse;
import com.cloudhopper.smpp.pdu.SubmitSm;
import com.cloudhopper.smpp.pdu.SubmitSmResp;
import com.cloudhopper.smpp.type.RecoverablePduException;
import com.cloudhopper.smpp.type.SmppChannelException;
import com.cloudhopper.smpp.type.SmppProcessingException;
import com.cloudhopper.smpp.type.SmppTimeoutException;
import com.cloudhopper.smpp.type.UnrecoverablePduException;
import com.cloudhopper.smpp.util.SmppUtil;
import com.google.common.util.concurrent.RateLimiter;
import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.smpp.SmsgSmppSessionHandler;
import gr.cytech.sendium.core.smpp.util.SmppServerUtil;
import gr.cytech.sendium.util.Constants;
import gr.cytech.sendium.util.MessageTrace;
import gr.cytech.sendium.util.MessageUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.channels.ClosedChannelException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;

public class SmppServerSessionHandler<M extends StandardMessage> implements SmsgSmppSessionHandler {
    public static final String DATE_FORMAT = "yyyy-MMM-dd HH:mm:ss.SS+z";
    private static final DateTimeFormatter dateTimeFormatter = new DateTimeFormatterBuilder()
            .appendPattern("yyyy-MMM-dd HH:mm:ss")
            .appendFraction(ChronoField.MICRO_OF_SECOND, 0, 6, true)
            .appendPattern("+z")
            .toFormatter();
    private static final Logger logger = LoggerFactory.getLogger(SmppServerSessionHandler.class);
    public long lastPduTimestamp;
    private final SmppSession session;
    private final Long sessionId;
    private final SmppServerWorker<M> worker;
    private final RateLimiter rateController;
    private final SmppSessionContext sessionContext;
    private final SubmitSmProcessor<M> submitProcessor;
    private String apiProduct;

    public SmppServerSessionHandler(SmppServerWorker<M> worker,
                                    Long sessionId,
                                    SmppSession session,
                                    SmppSessionContext sessionContext,
                                    SubmitSmProcessor<M> submitProcessor) {
        this.worker = worker;
        this.sessionId = sessionId;
        this.session = session;
        this.sessionContext = sessionContext;
        this.submitProcessor = submitProcessor;
        double rateLimit = worker.getMaxRate(getAccountId());
        this.rateController = RateLimiter.create(rateLimit > 0 ? rateLimit : Double.MAX_VALUE);
        this.lastPduTimestamp = System.currentTimeMillis();
    }

    public SmppServerSessionHandler(SmppServerWorker<M> worker,
                                    Long sessionId,
                                    SmppSession session) {
        this.worker = worker;
        this.sessionId = sessionId;
        this.session = session;
        this.sessionContext = null;
        this.submitProcessor = null;
        double rateLimit = worker.getMaxRate(getAccountId());
        this.rateController = RateLimiter.create(rateLimit > 0 ? rateLimit : Double.MAX_VALUE);
        this.lastPduTimestamp = System.currentTimeMillis();
    }

    /**
     * Called when a request PDU such as a "DeliverSM" has been received on a
     * session.  This method provides a simply way to return a response PDU.
     * If a non-null response PDU is returned, this pdu will be sent
     * back on the session's channel.  If the response PDU is null, then no
     * response will be sent back and its up to the implementation to send
     * back the response instead.
     *
     * @param pduRequest The request PDU received on this session
     * @return The response PDU to send back OR null if no response should be returned.
     */
    public PduResponse firePduRequestReceived(PduRequest pduRequest) {
        pduRequest.setReferenceObject(new Object[]{this, System.currentTimeMillis()});
        PduResponse resp;
        switch (pduRequest.getCommandId()) {
            case SmppConstants.CMD_ID_SUBMIT_SM:
                SubmitSm submitSm = (SubmitSm) pduRequest;
                try {
                    handleSubmitSm(submitSm);
                } catch (Exception e) {
                    logger.error("{}: handling of submit sm caused exception {}", this, MessageTrace.pdu(pduRequest), e);
                    resp = SmppServerUtil.createSubmitRsp(submitSm, SmppConstants.STATUS_SYSERR, null);
                    worker.enqueueOut(resp);
                }
                break;
            case SmppConstants.CMD_ID_ENQUIRE_LINK:
            case SmppConstants.CMD_ID_UNBIND:
                resp = pduRequest.createResponse();
                resp.setReferenceObject(pduRequest.getReferenceObject());
                logger.debug("Responding to command {}", pduRequest);
                worker.enqueueOut(resp);
                break;
            default:
                logger.debug("{}: received (invalid/not handled) pdu request: {}", this, pduRequest);
                resp = SmppServerUtil.createGenericNack(pduRequest, SmppConstants.STATUS_INVCMDID);
                worker.enqueueOut(resp);
                break;
        }
        //never return response
        //that would mean that the worker threads in the nio event loop group
        //would get stuck writing the response in the channel
        //instead let only threads in outExecutor to get stuck writing
        return null;
    }

    /**
     * Called when a request PDU has not received an associated response within
     * the expiry time.  Usually, this means the request should be retried.
     *
     * @param pduRequest The request PDU received on this session
     */
    public void firePduRequestExpired(PduRequest pduRequest) {
        logger.info("{}: received expired request PDU {}", this, MessageTrace.pdu(pduRequest));

        if (pduRequest.getCommandId() == SmppConstants.CMD_ID_DELIVER_SM) {
            try {
                Object[] arr = (Object[]) pduRequest.getReferenceObject();
                M original = (M) arr[1];

                worker.markAsUnpushed(original);
            } catch (Exception e) {
                logger.warn("{}: error while handling expired pdu request {}", this, MessageTrace.pdu(pduRequest), e);
            }
        }
    }

    /**
     * Called when the underlying channel of a session has been closed and it
     * wasn't at the request of our side.  This will either indicate the remote
     * system closed the socket OR the connection dropped in-between.  If the
     * session's actual "close" method was called, this won't be triggered.
     */
    public void fireChannelUnexpectedlyClosed() {
        logger.warn("{}: closed unexpectedly", this);
        worker.getBindHandler().getConnections().removeConnection(this);
    }

    /**
     * Called when a response PDU is received for a previously sent request PDU.
     * Only "expected" responses are passed to this method. An "expected" response
     * is a response that matches a previously sent request.  Both the original
     * request and the response along with other info is passed to this method.
     * <br />
     * NOTE: If another thread is "waiting" for a response, that thread will
     * receive it vs. this method.  This method will only receive expected
     * responses that were either sent "asynchronously" or received after the
     * originating thread timed out while waiting for a response.
     *
     * @param pduAsyncResponse The "expected" response PDU received on this session
     */
    public void fireExpectedPduResponseReceived(PduAsyncResponse pduAsyncResponse) {
        logger.trace("{}: received expected response PDU: {}", this, pduAsyncResponse.getResponse());

        /*
         * Its possible the response PDU really isn't the correct PDU we were waiting for,
         * so we should verify it. For example it is possible that a "Generic_Nack" could
         * be returned by the remote endpoint in response to a PDU.
         */
        if (pduAsyncResponse.getResponse().getCommandStatus() != SmppConstants.STATUS_OK) {
            logger.warn("{}: pdu response with error status received {}", this, MessageTrace.pdu(pduAsyncResponse.getResponse()));
        }
    }

    /**
     * Called when a response PDU is received for a request this session never sent.
     * Only "unexpected" responses are passed to this method. An "unexpected" response
     * is a response that does NOT match a previously sent request.  That can
     * either happen because it really is an invalid response OR another thread
     * that originated the request "cancelled" it.  Cancelling is VERY uncommon
     * so an invalid response is more likely.
     *
     * @param pduResponse The "unexpected" response PDU received on this session
     */
    public void fireUnexpectedPduResponseReceived(PduResponse pduResponse) {
        logger.warn("{}: received unexpected response PDU {}", this, MessageTrace.pdu(pduResponse));
    }

    /**
     * Called when an "unrecoverable" exception has been thrown downstream in
     * the session's pipeline.  The best example is a PDU that has an impossible
     * sequence number.  The recommended action is almost always to close the
     * session and attempt to rebind at a later time.
     *
     * @param e The exception
     */
    public void fireUnrecoverablePduException(UnrecoverablePduException e) {
        getSession().destroy();
        logger.warn("{}: destroyed because of unrecoverable pdu exception", this, e);
    }

    /**
     * Called when a "recoverable" exception has been thrown downstream in
     * the session's pipeline.  The best example is a PDU that may have been
     * missing some fields such as NULL byte.  A "recoverable" exception always
     * includes a "PartialPdu" which always contains enough information to
     * create a "NACK" back.  That's the recommended behavior of implementations --
     * to trigger a GenericNack for PduRequests.
     */
    public void fireRecoverablePduException(RecoverablePduException e) {
        PartialPdu partialPdu = (PartialPdu) e.getPartialPdu();

        logger.warn("{}: received malformed partial PDU", this, e);

        if (partialPdu.getReferenceObject() == null) {
            partialPdu.setReferenceObject(new Object[]{this, System.currentTimeMillis()});
        }
        GenericNack resp = SmppServerUtil.createGenericNack(partialPdu, SmppConstants.STATUS_UNKNOWNERR);

        worker.enqueueOut(resp);
    }

    /**
     * Called when any exception/throwable has been thrown downstream in
     * the session's pipeline that wasn't of the types: UnrecoverablePduException
     * or RecoverablePduException.
     */
    public void fireUnknownThrowable(Throwable t) {
        if (t != null) {
            if (t instanceof ClosedChannelException) {
                fireChannelUnexpectedlyClosed();
            } else {
                logger.warn("{}: received Unknown Throwable:", this, t);
            }
        }
    }

    /**
     * Lookup a "command_status" value and returns a String that represents a result
     * message (description) of what the value means.  A way to add helpful
     * debugging information into log files or management interfaces.  This value
     * is printed out via the toString() method for a PDU response.
     *
     * @param commandStatus The command_status field to lookup
     * @return A String representing a short description of what the command_status value represents. For example, a command_status of 0 usually means "OK"
     */
    public String lookupResultMessage(int commandStatus) {
        return SmppConstants.STATUS_MESSAGE_MAP.get(commandStatus);
    }

    /**
     * Lookup a name for the tag of a TLV and returns a String that represents a
     * name for it.  A way to add helpful debugging information into logfiles or
     * management interfaces.
     *
     * @param tag The TLV's tag value to lookup
     * @return A String representing a short name of what the tag value represents. For example, a tag of 0x001E usually means "receipted_message_id"
     */
    public String lookupTlvTagName(short tag) {
        return SmppConstants.TAG_NAME_MAP.get(tag);
    }

    /**
     * Called when ANY PDU received from connection.
     * This method could be used to sniff all incoming SMPP packet traffic,
     * and also permit/deny it from further processing.
     * Could be used for advanced packet logging, counters and filtering.
     *
     * @param pdu the pdu
     * @return boolean allow PDU processing. If false PDU would be discarded.
     */
    public boolean firePduReceived(Pdu pdu) {
        lastPduTimestamp = System.currentTimeMillis();

        // Accept pdu for processing up chain
        logger.trace("{}: firePduReceived {} ", this, pdu);

        //if the pdu is a submit / data /deliver request, try to apply rate limit
        if (pdu.isRequest() &&
                (pdu.getCommandId() == SmppConstants.CMD_ID_SUBMIT_SM ||
                        pdu.getCommandId() == SmppConstants.CMD_ID_DATA_SM ||
                        pdu.getCommandId() == SmppConstants.CMD_ID_DELIVER_SM)
        ) {
            if (!rateController.tryAcquire()) {
                pdu.setReferenceObject(new Object[]{this, System.currentTimeMillis()});
                logger.warn("{}: PDU throttled rate={} {}", this, rateController.getRate(), MessageTrace.pdu(pdu));
                GenericNack nack = SmppServerUtil.createGenericNack((PduRequest<?>) pdu, SmppConstants.STATUS_THROTTLED);
                worker.enqueueOut(nack);
                return false;
            }
        }

        if (getSession().hasCounters()) {
            if (pdu.isRequest()) {
                worker.checkGetInRequestStats();
            } else {
                worker.checkGetInResponseStats();
            }
        }

        return true;
    }

    /**
     * Called when ANY PDU dispatched from connection.
     * This method could be used to sniff all outgoing SMPP packet traffic,
     * and also permit/deny it from sending.
     * Could be used for advanced packet logging, counters and filtering.
     *
     * @param pdu the pdu
     * @return boolean allow PDU sending. If false PDU would be discarded.
     */
    public boolean firePduDispatch(Pdu pdu) {
        // Accept pdu for processing up chain
        logger.trace("{}: firePduDispatch {} ", this, pdu);

        if (getSession().hasCounters()) {
            if (pdu.isRequest()) {
                worker.checkGetOutRequestStats();
            } else {
                worker.checkGetOutResponseStats();
            }
        }

        return true;
    }

    public boolean sendPduResponse(PduResponse response) throws RecoverablePduException, SmppChannelException, UnrecoverablePduException, InterruptedException {
        if (session == null || !session.isBound()) {
            logger.warn("null/unbound session while trying to send response:{}", response);
            return false;
        }
        long processingStartTime = -1;
        if (response.getReferenceObject() instanceof Object[] ref) {
            if (ref.length > 1 && ref[1] instanceof Long l1) {
                processingStartTime = l1;
            }
        }
        if (processingStartTime > 0) {
            session.sendResponsePdu(response, processingStartTime);
        } else {
            session.sendResponsePdu(response);
        }
        return true;
    }

    public boolean sendPduRequest(PduRequest<?> request) throws RecoverablePduException, InterruptedException,
            SmppChannelException, UnrecoverablePduException, SmppTimeoutException {
        if (session == null) {
            logger.warn("null session while trying to send request:{}", request);
            return false;
        }
        var future = session.sendRequestPdu(request, session.getConfiguration().getRequestExpiryTimeout(), false);
        return true;
    }

    public SmppServerWorker<M> getWorker() {
        return worker;
    }

    public void handleSubmitSm(SubmitSm submitSm) {
        String userId = getAccountId();
        if (userId == null) {
            SubmitSmResp resp = SmppServerUtil.createSubmitRsp(submitSm, SmppConstants.STATUS_INVSYSID, null);
            worker.enqueueOut(resp);
            return;
        }

        try {
            Timestamp tstamp = validateScheduleDeliveryTime(submitSm);
            if (tstamp == null) {
                return;
            }

            SmppServerUtil.ValidatedMessageBody validatedMessageBody = validateShortMessage(submitSm);
            if (validatedMessageBody == null) {
                return;
            }

            InEvent<M> ine = submitProcessor.processSubmitSm(submitSm, sessionContext, validatedMessageBody, tstamp);
            worker.enqueueIn(ine);
        } catch (SmppProcessingException e) {
            logger.warn("{} error processing submit sm {}", this, MessageTrace.pdu(submitSm), e);
            SubmitSmResp resp = SmppServerUtil.createSubmitRsp(submitSm, e.getErrorCode(), null);
            worker.enqueueOut(resp);
        } catch (Exception e) {
            logger.error("{}: unexpected error processing submit sm {}", this, MessageTrace.pdu(submitSm), e);
            SubmitSmResp resp = SmppServerUtil.createSubmitRsp(submitSm, SmppConstants.STATUS_SYSERR, null);
            worker.enqueueOut(resp);
        }
    }

    protected SmppServerUtil.ValidatedMessageBody validateShortMessage(SubmitSm submitSm) {
        byte[] data = null;

        if (submitSm.getShortMessageLength() == 0) {
            // check if short message is in message payload TLV
            var messagePayloadTlv = submitSm.getOptionalParameter(SmppConstants.TAG_MESSAGE_PAYLOAD);
            if (messagePayloadTlv != null) {
                data = messagePayloadTlv.getValue();
            }
            if (data == null || data.length == 0) {
                SubmitSmResp resp = SmppServerUtil.createSubmitRsp(submitSm, SmppConstants.STATUS_INVMSGLEN, null);
                worker.enqueueOut(resp);
                return null;
            }
        } else {
            data = submitSm.getShortMessage();
        }

        String udh = null;
        if (SmppUtil.isUserDataHeaderIndicatorEnabled(submitSm.getEsmClass())) {
            int udhLength = data[0];
            byte[] udhBytes = new byte[udhLength + 1];
            System.arraycopy(data, 0, udhBytes, 0, udhBytes.length);
            udh = HexUtil.toHexString(udhBytes);
            byte[] bodyBytes = new byte[data.length - udhBytes.length];
            System.arraycopy(data, udhBytes.length, bodyBytes, 0, bodyBytes.length);

            data = bodyBytes;
        }

        int maxLength;
        int smtype;
        String text;
        switch (submitSm.getDataCoding()) {
            case SmppConstants.DATA_CODING_UCS2:  // UNICODE MESSAGE
                smtype = StandardMessage.MSG_UCS2;
                maxLength = Constants.UCS_LENGTH;
                text = SmppServerUtil.getMessageBody(data, worker.getCharsetUcs2());
                break;
            case SmppConstants.DATA_CODING_8BIT:
                smtype = StandardMessage.MSG_BINARY;
                maxLength = Constants.BINARY_LENGTH;
                text = HexUtil.toHexString(data);
                break;
            case SmppConstants.DATA_CODING_DEFAULT:
            case SmppConstants.DATA_CODING_GSM:
                smtype = StandardMessage.MSG_TEXT;
                maxLength = Constants.GSM_LENGTH;
                text = SmppServerUtil.getMessageBody(data, worker.getCharsetGsm());
                break;
            case SmppConstants.DATA_CODING_LATIN1:
                smtype = StandardMessage.MSG_TEXT;
                maxLength = Constants.GSM_LENGTH;
                text = SmppServerUtil.getMessageBody(data, worker.getCharsetLatin1());
                break;
            default:
                SubmitSmResp resp = SmppServerUtil.createSubmitRsp(submitSm, VendorSpecificConstants.STATUS_RINVDCS, null);
                worker.enqueueOut(resp);
                return null;
        }

        if (text == null || text.isEmpty()) {
            SubmitSmResp resp = SmppServerUtil.createSubmitRsp(submitSm, SmppConstants.STATUS_INVMSGLEN, null);
            worker.enqueueOut(resp);
            return null;
        }
        text = MessageUtil.validateText(udh, text, smtype, maxLength);
        text = text.replace("\0", "");
        return new SmppServerUtil.ValidatedMessageBody(text, udh, smtype);
    }

    public Timestamp validateScheduleDeliveryTime(SubmitSm submitSm) {
        Timestamp tstamp;
        try {
            if (submitSm.getScheduleDeliveryTime() != null && !submitSm.getScheduleDeliveryTime().isEmpty()) {
                Instant instant = Instant.from(dateTimeFormatter.parse(submitSm.getScheduleDeliveryTime()));
                if (instant.isBefore(Instant.now())) {
                    instant = Instant.now();
                }
                tstamp = Timestamp.from(instant);
            } else {
                tstamp = new Timestamp(System.currentTimeMillis());
            }
        } catch (Exception e) {
            SubmitSmResp resp = SmppServerUtil.createSubmitRsp(submitSm, SmppConstants.STATUS_INVSCHED, null);
            worker.enqueueOut(resp);
            return null;
        }
        return tstamp;
    }

    public SmppSession getSession() {
        return session;
    }

    public Long getSessionId() {
        return sessionId;
    }

    public String getAccountId() {
        SmppSession s = getSession();
        if (s == null || s.getConfiguration() == null) {
            return null;
        }
        return s.getConfiguration().getName();
    }

    public String getApiProduct() {
        return apiProduct;
    }

    public void setApiProduct(String apiProduct) {
        this.apiProduct = apiProduct;
    }

    public String getSystemId() {
        SmppSession s = getSession();
        if (s == null || s.getConfiguration() == null) {
            return null;
        }
        return s.getConfiguration().getSystemId();
    }

    public void configMaxRate(String accountId) {
        double rate = worker.getMaxRate(accountId);
        logger.debug("setting rate for handler:{} of user:{} to:{}", this, getAccountId(), rate);
        this.rateController.setRate(rate);
    }

    public String toString() {
        return "SmppSessionHandler{Id:" +
                sessionId +
                ", Name:" +
                ((getAccountId() != null) ? getAccountId() : "null") +
                "}";
    }

    public double getRate() {
        return rateController.getRate();
    }

    @Override
    public long getLastPduTimestamp() {
        return lastPduTimestamp;
    }

    @Override
    public boolean isBackupConnection() {
        return false;
    }
}
