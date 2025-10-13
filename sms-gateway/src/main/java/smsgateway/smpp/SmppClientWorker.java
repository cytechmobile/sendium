package smsgateway.smpp;

import com.cloudhopper.commons.charset.CharsetUtil;
import com.cloudhopper.commons.util.HexUtil;
import com.cloudhopper.commons.util.StringUtil;
import com.cloudhopper.smpp.SmppBindType;
import com.cloudhopper.smpp.SmppConstants;
import com.cloudhopper.smpp.SmppSessionConfiguration;
import com.cloudhopper.smpp.pdu.BaseSm;
import com.cloudhopper.smpp.pdu.DeliverSm;
import com.cloudhopper.smpp.pdu.EnquireLink;
import com.cloudhopper.smpp.pdu.PduResponse;
import com.cloudhopper.smpp.pdu.SubmitSm;
import com.cloudhopper.smpp.type.Address;
import com.cloudhopper.smpp.type.SmppInvalidArgumentException;
import com.cloudhopper.smpp.util.DeliveryReceipt;
import com.cloudhopper.smpp.util.SmppUtil;
import com.google.common.base.Strings;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import smsgateway.dto.DlrForwardingPayload;
import smsgateway.dto.IncomingSms;
import smsgateway.providers.LogProvider;
import smsgateway.routing.destination.AbstractOutWorker;
import smsgateway.routing.destination.MessageDestination;
import smsgateway.services.DlrForwardingService;
import smsgateway.services.DlrMappingService;

public class SmppClientWorker extends AbstractOutWorker implements Runnable, MessageDestination {
    public static final String TYPE = "SMPP";

    public static final String CHARSET_HEX = "HEX";

    protected static final Map<Byte, String> codingsMap =
            new HashMap<>(
                    Map.of(
                            SmppConstants.DATA_CODING_DEFAULT, CharsetUtil.NAME_GSM,
                            SmppConstants.DATA_CODING_GSM, CharsetUtil.NAME_GSM,
                            SmppConstants.DATA_CODING_LATIN1, CharsetUtil.NAME_ISO_8859_1,
                            SmppConstants.DATA_CODING_UCS2, CharsetUtil.NAME_UCS_2,
                            SmppConstants.DATA_CODING_8BIT, CHARSET_HEX));
    protected static final Map<Integer, Byte> dcsMap =
            new HashMap<>(
                    Map.of(
                            0, SmppConstants.DATA_CODING_DEFAULT,
                            4, SmppConstants.DATA_CODING_8BIT,
                            8, SmppConstants.DATA_CODING_UCS2));
    protected final Logger logger;
    protected final AtomicInteger msgRefNumGenerator = new AtomicInteger();

    private ScheduledFuture<?> enquireLinkTaskFuture;
    DlrForwardingService dlrForwardingService;

    protected SmppClientSessionHandler smppClientSessionHandler;
    protected SmppClientHolder smppClientHolder;
    private volatile boolean running = true;
    private Thread workerThread;

    @Inject
    public SmppClientWorker(
            VendorConf vendor,
            SmppClientHolder smppClientHolder,
            DlrForwardingService dlrForwardingService,
            DlrMappingService dlrMappingService) { // New parameter
        super(vendor, dlrMappingService);
        this.logger = LogProvider.getSmppClientLogger(vendor.getId());
        this.smppClientHolder = smppClientHolder;
        this.dlrForwardingService = dlrForwardingService;
    }

    public VendorConf getVendor() {
        return vendorConf;
    }

    private void sendEnquireLinkPdu() {
        if (smppClientSessionHandler == null
                || smppClientSessionHandler.getSession() == null
                || !smppClientSessionHandler.getSession().isBound()) {
            logger.warn(
                    "Worker '{}': Cannot send enquire_link, session not bound.",
                    vendorConf.getId());
            // Optionally, could try to stop the task here if the session is consistently not bound,
            // but the start/stop is primarily managed by connection events.
            return;
        }
        logger.debug(
                "Worker '{}': Sending enquire_link to keep session alive.", vendorConf.getId());
        EnquireLink enquireLink = new EnquireLink();
        try {
            smppClientSessionHandler
                    .getSession()
                    .sendRequestPdu(
                            enquireLink,
                            TimeUnit.SECONDS.toMillis(
                                    vendorConf.getEnquireLinkIntervalSeconds()), // Use configured
                            // interval as
                            // timeout for the
                            // PDU response
                            false); // Do not wait for response here as it's a scheduled task
        } catch (Exception e) { // SmppTimeoutException, SmppChannelException, InterruptedException,
            // RecoverablePduException, UnrecoverablePduException
            logger.warn(
                    "Worker '{}': Failed to send enquire_link: {}",
                    vendorConf.getId(),
                    e.getMessage());
            // It's crucial to handle session state changes properly.
            // If enquire_link fails, the session might be dead.
            // The existing session handler or connection logic should detect this (e.g., via
            // channelInactive or PDU response timeouts).
            // For now, we rely on the main connection loop to detect and handle disconnections.
            // smppClientSessionHandler.handleFailedEnquireLink(); // This method does not exist,
            // let's see if it's needed later.
            // For now, just log. The main loop should eventually detect unbinding.
        }
    }

    public void startEnquireLinkTask() {
        stopEnquireLinkTask(); // Cancel any existing task first
        if (vendorConf.getEnquireLinkIntervalSeconds() <= 0) {
            logger.info(
                    "Worker '{}': Enquire_link interval is not configured or is zero/negative. Task will not be scheduled.",
                    vendorConf.getId());
            return;
        }

        logger.info(
                "Worker '{}': Scheduling enquire_link task with interval {} seconds.",
                vendorConf.getId(),
                vendorConf.getEnquireLinkIntervalSeconds());
        try {
            this.enquireLinkTaskFuture =
                    smppClientHolder
                            .getEnquireLinkExecutor()
                            .scheduleAtFixedRate(
                                    this::sendEnquireLinkPdu,
                                    vendorConf.getEnquireLinkIntervalSeconds(), // Initial delay
                                    vendorConf.getEnquireLinkIntervalSeconds(), // Period
                                    TimeUnit.SECONDS);
        } catch (Exception e) {
            logger.error(
                    "Worker '{}': Failed to schedule enquire_link task: {}",
                    vendorConf.getId(),
                    e.getMessage(),
                    e);
        }
    }

    public void stopEnquireLinkTask() {
        if (this.enquireLinkTaskFuture != null && !this.enquireLinkTaskFuture.isDone()) {
            logger.info("Worker '{}': Stopping enquire_link task.", vendorConf.getId());
            this.enquireLinkTaskFuture.cancel(false); // false: allow running task to complete
        }
        this.enquireLinkTaskFuture = null; // Ensure it can be rescheduled
    }

    @Override
    public void process(IncomingSms message, String ruleName, String destinationId) {
        logger.info(
                "Worker '{}': Queuing message from '{}' for rule '{}', destination '{}'",
                vendorConf.getId(),
                message.getFrom(),
                ruleName,
                destinationId);
        boolean offered = messageQueue.offer(message);
        if (!offered) {
            logger.warn(
                    "Worker '{}': Failed to queue message from '{}'",
                    vendorConf.getId(),
                    message.getFrom());
        }
    }

    @Override
    public void run() {
        this.workerThread = Thread.currentThread();
        logger.info(
                "SmppClientWorker for vendor '{}' on host '{}' started.",
                vendorConf.getId(),
                vendorConf.getHost());

        while (this.running) {
            if (smppClientSessionHandler == null
                    || smppClientSessionHandler.getSession() == null
                    || !smppClientSessionHandler.getSession().isBound()) {
                logger.info(
                        "Worker '{}': Not connected. Attempting to connect.", vendorConf.getId());
                if (!addConnection()) {
                    logger.warn(
                            "Worker '{}': Failed to connect to vendor. Retrying in {} seconds.",
                            vendorConf.getId(),
                            vendorConf.getReconnectIntervalSeconds());
                    try {
                        Thread.sleep(vendorConf.getReconnectIntervalSeconds() * 1000L);
                    } catch (InterruptedException e) {
                        logger.warn(
                                "Worker '{}': Sleep interrupted during reconnect delay. Checking running state.",
                                vendorConf.getId());
                        // The loop condition 'while (this.running)' will handle termination if
                        // needed.
                    }
                    continue; // Try to connect again in the next iteration.
                }
                // Successfully connected, proceed to message processing or next check
            }

            // Inner loop for processing messages only if connected and bound
            while (this.running
                    && smppClientSessionHandler != null
                    && smppClientSessionHandler.getSession() != null
                    && smppClientSessionHandler.getSession().isBound()) {
                IncomingSms sms = null;
                try {
                    sms = messageQueue.poll(1, TimeUnit.SECONDS); // Poll with timeout

                    if (sms != null) {
                        if (this.rateLimiter != null) {
                            logger.info("rate current: {}", this.rateLimiter.getRate());
                            this.rateLimiter.acquire();
                        }

                        // Message received, process it
                        try {
                            logger.info(
                                    "Worker '{}': Processing message from '{}' to '{}': {}",
                                    vendorConf.getId(),
                                    sms.getFrom(),
                                    sms.getTo(),
                                    sms.getText());
                            sendMessage(sms);
                        } catch (Exception e) {
                            String messageIdInfo = "unknown";
                            if (sms != null) {
                                messageIdInfo =
                                        "from "
                                                + (sms.getFrom() != null ? sms.getFrom() : "null")
                                                + " to "
                                                + (sms.getTo() != null ? sms.getTo() : "null");
                            }
                            logger.error(
                                    "Worker '{}': Exception processing message ({})",
                                    vendorConf.getId(),
                                    messageIdInfo,
                                    e);
                        }
                    }
                    // The enquire_link logic that was here is now removed.
                    // The health check is handled by the scheduled enquireLinkTask.

                } catch (InterruptedException e) {
                    logger.warn(
                            "Worker '{}': Interrupted while polling for message. Stopping...",
                            vendorConf.getId());
                    this.running = false;
                    Thread.currentThread().interrupt(); // Re-interrupt the thread
                    break;
                }
            }

            // If the inner loop exited and we are still 'running', it means an unexpected
            // disconnection.
            if (this.running
                    && (smppClientSessionHandler == null
                            || smppClientSessionHandler.getSession() == null
                            || !smppClientSessionHandler.getSession().isBound())) {
                logger.warn(
                        "Worker '{}': Unexpectedly disconnected. Attempting to reconnect.",
                        vendorConf.getId());
                // The outer loop will handle the reconnection attempt.
            }
        }
        logger.info("SmppClientWorker for vendor '{}' stopped.", vendorConf.getId());
    }

    public void stopWorker() {
        logger.info("Worker '{}': Initiating shutdown...", vendorConf.getId());
        this.running = false;
        stopEnquireLinkTask(); // Add this line

        // Attempt to unbind and destroy the session
        // removeConnection handles null smppClientSessionHandler and null session
        removeConnection(smppClientSessionHandler, true, false);

        if (workerThread != null && workerThread.isAlive()) {
            logger.info("Worker '{}': Interrupting worker thread...", vendorConf.getId());
            workerThread.interrupt();
        }
        logger.info("Worker '{}': Shutdown sequence completed.", vendorConf.getId());
    }

    protected boolean sendMessage(IncomingSms message) {
        List<SubmitSm> requests;
        try {
            requests = generateSubmitRequest(message);
        } catch (Exception e) {
            logger.warn("Caught exception while generating SMPP request(s) for msg:{}", message, e);
            return false;
        }
        for (int i = 0; i < requests.size(); i++) {
            SubmitSm submitSm = requests.get(i);
            try {
                smppClientSessionHandler.session.sendRequestPdu(submitSm, 30, false);
            } catch (Exception e) {
                IncomingSms originalMessage = (IncomingSms) submitSm.getReferenceObject();
                logger.warn(
                        "Worker '{}': Failed to send ({}} of {}) of  message for original message (from {} to {})."
                                + " message submission cancelled. Error: {}",
                        i,
                        vendorConf.getId(),
                        requests.size(),
                        originalMessage.getFrom(),
                        originalMessage.getTo(),
                        e.getMessage(),
                        e);
                return false;
            }
        }
        return true;
    }

    public String getFullName() {
        return vendorConf.getId();
    }

    public PduResponse parseDlrAndCreateResponse(DeliverSm deliverSm) {
        try {
            final var from = deliverSm.getSourceAddress().getAddress();
            final var to = deliverSm.getDestAddress().getAddress();
            String charset = codingsMap.get(deliverSm.getDataCoding());
            String dlrBody =
                    CHARSET_HEX.equals(charset)
                            ? new String(deliverSm.getShortMessage())
                            : CharsetUtil.decode(deliverSm.getShortMessage(), charset);

            if (!Strings.isNullOrEmpty(dlrBody)
                    && !charset.equals(CharsetUtil.NAME_GSM)
                    && !dlrBody.startsWith("id:")) {
                dlrBody = attemptGsmReparse(deliverSm.getShortMessage(), dlrBody, deliverSm);
            }

            if (Strings.isNullOrEmpty(dlrBody)) {
                dlrBody = new String(deliverSm.getShortMessage());
            }

            var receipt = DeliveryReceipt.parseShortMessage(dlrBody, ZoneOffset.UTC, false, false);
            String smscid = receipt.getMessageId(); // This is the vendorMessageId
            String internalId = dlrMappingService.getInternalId(smscid);

            if (internalId != null) {
                DlrForwardingPayload payload = dlrMappingService.getDlrPayload(internalId);
                if (payload != null) {
                    payload.setStatus(DeliveryReceipt.toStateText(receipt.getState()));
                    payload.setErrorCode(String.valueOf(receipt.getErrorCode()));
                    payload.setRawDlr(dlrBody);
                    payload.setProcessedAt(Instant.now()); // Set processed timestamp

                    dlrMappingService.storeDlrPayload(internalId, payload); // Store updated payload

                    if (dlrForwardingService != null) {
                        dlrForwardingService.forwardDlr(
                                payload, vendorConf.getId()); // Pass the whole payload
                        logger.info(
                                "Worker '{}': Updated and forwarded DlrForwardingPayload for internalId: {}, smscid: {}",
                                vendorConf.getId(),
                                internalId,
                                smscid);
                    } else {
                        logger.warn(
                                "Worker '{}': DlrForwardingService not injected. DLR for internalId: {} not forwarded.",
                                vendorConf.getId(),
                                internalId);
                    }
                } else {
                    logger.warn(
                            "Worker '{}': DlrForwardingPayload not found for internalId: {}. DLR from smscid: {} will not be fully processed.",
                            vendorConf.getId(),
                            internalId,
                            smscid);
                }
            } else {
                logger.warn(
                        "Worker '{}': No internalId found for smscid: {}. DLR cannot be fully processed or forwarded via stateful payload.",
                        vendorConf.getId(),
                        smscid);
                // If you still need to forward DLRs that don't have an internalId (e.g. for some
                // audit),
                // you might need a separate call to dlrForwardingService or adapt it to handle such
                // cases.
                // For now, as per instructions, we are not calling it if internalId is missing.
            }
        } catch (Exception e) {
            // our own extended delivery receipt parsing method will not throw exception for dlr
            // field validation
            // so this means that something else went really wrong
            logger.warn("caught exception while parsing dlr: " + deliverSm + " error:", e);
            PduResponse resp = deliverSm.createResponse();
            resp.setCommandStatus(SmppConstants.STATUS_SYSERR);
            return resp;
        }

        return deliverSm.createResponse();
    }

    public PduResponse parseMoAndCreateResponse(BaseSm request) {
        byte[] data = request.getShortMessage();
        if (data == null || data.length == 0) {
            var messagePayloadTlv = request.getOptionalParameter(SmppConstants.TAG_MESSAGE_PAYLOAD);
            if (messagePayloadTlv != null) {
                data = messagePayloadTlv.getValue();
            }
        }
        String udh = null;
        if (SmppUtil.isUserDataHeaderIndicatorEnabled(request.getEsmClass())) {
            int udhLength = data[0];
            byte[] udhBytes = new byte[udhLength + 1];
            System.arraycopy(data, 0, udhBytes, 0, udhBytes.length);
            udh = HexUtil.toHexString(udhBytes);
            byte[] bodyBytes = new byte[data.length - udhBytes.length];
            System.arraycopy(data, udhBytes.length, bodyBytes, 0, bodyBytes.length);

            data = bodyBytes;
        }

        String charset = codingsMap.get(request.getDataCoding());
        String text;
        if (CHARSET_HEX.equals(charset)) {
            text = HexUtil.toHexString(data);
        } else {
            text = CharsetUtil.decode(data, charset);
        }
        // todo create and forward MO
        return request.createResponse();
    }

    private String attemptGsmReparse(
            byte[] shortMessage, String originalDlrBody, DeliverSm deliverSm) {
        try {
            String gsmBody = CharsetUtil.decode(shortMessage, CharsetUtil.NAME_GSM);
            if (!Strings.isNullOrEmpty(gsmBody) && gsmBody.startsWith("id:")) {
                logger.debug(
                        "Successfully reparsed DLR with GSM charset for message from {}.",
                        deliverSm.getSourceAddress().getAddress());
                return gsmBody;
            }
        } catch (Exception e) {
            logger.warn(
                    "Error trying to re-parse DLR with GSM charset for PDU from {}: {}. Original DLR body: '{}'. Error: {}",
                    deliverSm.getSourceAddress().getAddress(),
                    HexUtil.toHexString(shortMessage),
                    originalDlrBody,
                    e.getMessage());
        }
        return originalDlrBody; // Return original if reparse fails or is not applicable
    }

    protected void handleResponse(
            SmppClientSessionHandler handler,
            int statusCode,
            String respMessageId,
            IncomingSms msg) {
        logger.info(
                "Received response:{}-{} with smscid:{} for msg:{}",
                statusCode,
                handler.lookupResultMessage(statusCode),
                respMessageId,
                msg);
        if (statusCode == SmppConstants.STATUS_OK && respMessageId != null) {
            dlrMappingService.updateDlr(respMessageId, msg, "SENT");
        } else if (statusCode != SmppConstants.STATUS_OK) {
            logger.warn(
                    "Worker '{}': Received non-OK status ({}) for message (internalId: {}, smscid: {}). DLR status not set to SENT.",
                    vendorConf.getId(),
                    statusCode,
                    msg.getInternalId(),
                    respMessageId);
        }
        // If respMessageId is null but status is OK, it's unusual. Log if necessary.
        // If status is not OK, existing logic or future enhancements can handle it.
    }

    protected boolean addConnection() {
        SmppSessionConfiguration configuration = new SmppSessionConfiguration();
        configuration.setName(vendorConf.getId());
        configuration.setHost(vendorConf.getHost());
        configuration.setPort(vendorConf.getPort());
        configuration.setSystemId(vendorConf.getSystemId());
        configuration.setPassword(vendorConf.getPassword());
        configuration.setType(SmppBindType.TRANSCEIVER);

        try {
            final var handler = new SmppClientSessionHandler(this, dlrMappingService);
            if (smppClientHolder.resource() == null) {
                logger.info("smppClientHolder is not yet ready");
                Thread.sleep(1000);
            }
            final var session = smppClientHolder.resource().bind(configuration, handler);
            if (session == null || !session.isBound()) {
                if (session != null) {
                    try {
                        session.destroy();
                    } catch (Exception e) {
                        logger.warn("received not bound session and failed to destroy it", e);
                    }
                }
                throw new Exception("null session returned from bind");
            } else {
                logger.info("Established connection: [{}]", vendorConf);
            }
            handler.setSession(session);
            smppClientSessionHandler = handler;
            startEnquireLinkTask(); // Add this line
            return true;

        } catch (Exception e) {
            logger.warn(
                    "failed to add connection [{}] due to error: {}", vendorConf, e.getMessage());
        }

        return false;
    }

    protected boolean removeConnection(
            SmppClientSessionHandler handler, boolean unbind, boolean checkConnectivity) {
        stopEnquireLinkTask(); // Add this line
        if (handler == null) {
            return false;
        }
        // todo
        // sessionHandlers.remove(handler);

        if (handler.session != null) {
            if (unbind) {
                try {
                    handler.session.unbind(10);
                } catch (Exception e) {
                    logger.warn("Exception while unbinding. Moving on to destroying the session");
                }
            }
            handler.session.destroy();
        }

        return true;
    }

    public List<SubmitSm> generateSubmitRequest(IncomingSms pMsg)
            throws SmppInvalidArgumentException {
        List<SubmitSm> requests = new ArrayList<>();
        String effectiveCharset = pMsg.getCoding();
        if (effectiveCharset == null) {
            effectiveCharset = CharsetUtil.NAME_GSM;
            logger.debug(
                    "Message from {} to {} had null coding, defaulting to GSM for text encoding.",
                    pMsg.getFrom(),
                    pMsg.getTo());
        }

        byte dataCoding = getDcsForMessageCoding(effectiveCharset);
        byte[][] ccatBodies;

        if (CHARSET_HEX.equals(pMsg.getCoding())) { // Check original coding for HEX
            dataCoding = SmppConstants.DATA_CODING_8BIT;
            ccatBodies = new byte[1][];
            ccatBodies[0] = HexUtil.toByteArray(pMsg.getText());
        } else {
            boolean ccat8Bit = true; // This logic seems to determine UDH type, might need review
            // if charset implies different UDH needs
            int msgRefNum = generateMessageReferenceNumber(ccat8Bit);
            // Use effectiveCharset for splitting, which is guaranteed non-null
            ccatBodies = splitMessage(pMsg.getText(), effectiveCharset, msgRefNum, ccat8Bit);
        }

        Address srcAddr = getSourceAddress(pMsg.getFrom());
        Address dstAddr =
                new Address(
                        SmppConstants.TON_INTERNATIONAL, SmppConstants.NPI_UNKNOWN, pMsg.getTo());
        for (byte[] shortText : ccatBodies) {
            SubmitSm submit = new SubmitSm();

            submit.setSourceAddress(srcAddr);
            submit.setDestAddress(dstAddr);
            submit.setRegisteredDelivery(SmppConstants.REGISTERED_DELIVERY_SMSC_RECEIPT_REQUESTED);
            // if we have concatenated sms, or if we had udh
            if (ccatBodies.length > 1) {
                // Set User Data Header Indicator if message is concatenated
                submit.setEsmClass(SmppConstants.ESM_CLASS_UDHI_MASK);
            }
            submit.setDataCoding(dataCoding);
            submit.setShortMessage(shortText);

            submit.setReferenceObject(pMsg);
            requests.add(submit);
        }

        return requests;
    }

    public static Address getSourceAddress(String from) {
        byte srcTon = SmppConstants.TON_ALPHANUMERIC;
        byte srcNpi = SmppConstants.NPI_UNKNOWN;
        if (StringUtil.containsOnlyDigits(from)) {
            srcTon = SmppConstants.TON_INTERNATIONAL;
        }
        return new Address(srcTon, srcNpi, from);
    }

    public byte getDcsForMessageCoding(String coding) {
        if (CharsetUtil.NAME_GSM.equals(coding)) {
            return dcsMap.get(0);
        } else if (CharsetUtil.NAME_UCS_2.equals(coding)) {
            return dcsMap.get(8);
        }
        return dcsMap.get(0);
    }

    protected int generateMessageReferenceNumber(boolean ccat8bit) {
        int newVal = msgRefNumGenerator.incrementAndGet();
        return ccat8bit ? (byte) newVal : (short) newVal;
    }

    public static final int MAX_MULTIPART_MSG_SEGMENT_SIZE_UCS2 = 134;
    public static final int MAX_SINGLE_MSG_SEGMENT_SIZE_UCS2 = 140;
    public static final int MAX_MULTIPART_MSG_SEGMENT_SIZE_7BIT = 153;
    public static final int MAX_SINGLE_MSG_SEGMENT_SIZE_7BIT = 160;

    // UDH constants for Segmentation and Reassembly (SAR)
    // IEI (Information Element Identifier) for 8-bit reference number SAR
    private static final byte UDH_IEI_SAR_8BIT_REF = 0x00;
    // IEL (Information Element Length) for 8-bit reference number SAR (3 bytes data: ref_num,
    // max_seq, seq_num)
    private static final byte UDH_IEL_SAR_8BIT_REF = 0x03;

    // IEI (Information Element Identifier) for 16-bit reference number SAR
    private static final byte UDH_IEI_SAR_16BIT_REF = 0x08;
    // IEL (Information Element Length) for 16-bit reference number SAR (4 bytes data: ref_num(2),
    // max_seq, seq_num)
    private static final byte UDH_IEL_SAR_16BIT_REF = 0x04;

    public static byte[][] splitMessage(
            String messageBody, String charSet, int messageRefNum, boolean ccat8bit) {
        int maximumSingleMessageSize;
        int maximumMultipartMessageSegmentSize;
        byte[] byteSingleMessage = CharsetUtil.encode(messageBody, charSet);
        if (!CharsetUtil.NAME_UCS_2.equals(charSet) && !"ISO-10646-UCS-2".equals(charSet)) {
            maximumSingleMessageSize = MAX_SINGLE_MSG_SEGMENT_SIZE_7BIT;
            maximumMultipartMessageSegmentSize = MAX_MULTIPART_MSG_SEGMENT_SIZE_7BIT;
        } else {
            maximumSingleMessageSize = MAX_SINGLE_MSG_SEGMENT_SIZE_UCS2;
            maximumMultipartMessageSegmentSize = MAX_MULTIPART_MSG_SEGMENT_SIZE_UCS2;
        }

        byte[][] byteMessagesArray;
        if (byteSingleMessage.length > maximumSingleMessageSize) {
            // split message according to the maximum length of a segment
            byteMessagesArray =
                    splitMessage(
                            byteSingleMessage,
                            messageRefNum,
                            maximumMultipartMessageSegmentSize,
                            ccat8bit);
        } else {
            byteMessagesArray = new byte[][] {byteSingleMessage};
        }

        return byteMessagesArray;
    }

    /**
     * Splits a message to multiple parts with correct UDH in the start of the body
     *
     * @param message the original message that will be split to multiple parts
     * @param messageRefNum the message reference number that all parts will refer to
     * @param ccat8bit whether 8-bit concatenation header will be used (otherwise 16-bit
     *     concatenation header will be used)
     * @return the split message parts in separate byte arrays
     */
    private static byte[][] splitMessage(
            byte[] message,
            int messageRefNum,
            int maximumMultipartMessageSegmentSize,
            boolean ccat8bit) {
        byte udhieHeaderLength;
        byte udhieIdentifierSar;
        byte udhieSarLength;
        byte[] referenceNumber;

        // generate udh data and reference number
        if (ccat8bit) {
            // Total UDH length for 8-bit SAR:
            // 1 (UDHL - User Data Header Length)
            // + 1 (IEI - Information Element Identifier for SAR)
            // + 1 (IEL - Information Element Length for SAR)
            // + 1 (Reference number)
            // + 1 (Total segments)
            // + 1 (Segment sequence number)
            // = 6 bytes. The UDHL itself is 0x05 (meaning 5 bytes follow it).
            udhieHeaderLength = 0x05; // UDHL: Length of UDH, excluding this byte.
            udhieIdentifierSar = UDH_IEI_SAR_8BIT_REF; // IEI for SAR (0x00 for 8-bit ref#)
            udhieSarLength = UDH_IEL_SAR_8BIT_REF; // IEL for SAR (0x03 for 8-bit ref#)
            referenceNumber = copyShortByte(messageRefNum); // Message reference number (1 byte)
        } else {
            // Total UDH length for 16-bit SAR:
            // 1 (UDHL) + 1 (IEI) + 1 (IEL) + 2 (Reference number) + 1 (Total segments) + 1 (Segment
            // sequence number)
            // = 7 bytes. The UDHL itself is 0x06.
            udhieHeaderLength = 0x06; // UDHL: Length of UDH, excluding this byte.
            udhieIdentifierSar = UDH_IEI_SAR_16BIT_REF; // IEI for SAR (0x08 for 16-bit ref#)
            udhieSarLength = UDH_IEL_SAR_16BIT_REF; // IEL for SAR (0x04 for 16-bit ref#)
            referenceNumber = copyShort2Bytes(messageRefNum); // Message reference number (2 bytes)
        }

        // determine how many messages have to be sent
        int numberOfSegments = message.length / maximumMultipartMessageSegmentSize;
        int messageLength = message.length;
        if (numberOfSegments > 255) {
            numberOfSegments = 255;
            messageLength = numberOfSegments * maximumMultipartMessageSegmentSize;
        }
        if ((messageLength % maximumMultipartMessageSegmentSize) > 0) {
            numberOfSegments++;
        }

        // prepare array for all of the msg segments
        byte[][] segments = new byte[numberOfSegments][];

        int lengthOfData;

        // split the message adding required headers
        for (int i = 0; i < numberOfSegments; i++) {
            if (numberOfSegments - i == 1) {
                lengthOfData = messageLength - i * maximumMultipartMessageSegmentSize;
            } else {
                lengthOfData = maximumMultipartMessageSegmentSize;
            }

            // new array to store the header
            segments[i] = new byte[(ccat8bit ? 6 : 7) + lengthOfData];

            // UDH header
            // doesn't include itself, its header length
            segments[i][0] = udhieHeaderLength;
            // SAR identifier
            segments[i][1] = udhieIdentifierSar;
            // SAR length
            segments[i][2] = udhieSarLength;
            // reference number (same for all message parts)
            int segmentIdx = 3;
            for (byte ref : referenceNumber) {
                segments[i][segmentIdx++] = ref;
            }
            // total number of segments
            segments[i][segmentIdx++] = (byte) numberOfSegments;
            // segment number
            segments[i][segmentIdx++] = (byte) (i + 1);

            // copy the data into the array
            System.arraycopy(
                    message,
                    (i * maximumMultipartMessageSegmentSize),
                    segments[i],
                    segmentIdx,
                    lengthOfData);
        }
        return segments;
    }

    public static byte[] copyShortByte(int integer) {
        return new byte[] {(byte) (integer & 0x000000ff)};
    }

    public static byte[] copyShort2Bytes(int integer) {
        byte[] bytes = new byte[2];
        bytes[0] = (byte) ((integer >> 8) & 0x0000ff);
        bytes[1] = (byte) (integer & 0x000000ff);

        return bytes;
    }
}
