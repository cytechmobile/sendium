package gr.cytech.sendium.core.worker;

import gr.cytech.sendium.core.AbstractOutWorker;
import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.util.SecurityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;

public class InMemoryMessageTracker implements Tracker<StandardMessage> {

    private static final Logger logger = LoggerFactory.getLogger(InMemoryMessageTracker.class);
    AbstractOutWorker<StandardMessage> outWorker;
    private final ConcurrentLinkedQueue<StandardMessage> dlrQueue = new ConcurrentLinkedQueue<>();

    public InMemoryMessageTracker(AbstractOutWorker<StandardMessage> worker) {
        this.outWorker = worker;
    }

    @Override
    public void init() {
        logger.info("InMemoryMessageTracker initialized");
    }

    @Override
    public boolean stop() {
        logger.info("InMemoryMessageTracker stopping");
        return true;
    }

    @Override
    public void configure(String key, String newValue, String oldValue) {
        logger.debug("Configure: key={}, newValue={}, oldValue={}", key, newValue, oldValue);
    }

    @Override
    public int updateSendStatusAndExtID(String smsid, StandardMessage pMsg, String smscid) {
        smsid = pMsg.serial; //in our case no hash needed
        if (smsid != null && !smsid.isEmpty() && smscid != null && !smscid.isEmpty()) {
            outWorker.getWorkerResources().getDlrService().linkOperatorId(smsid, smscid);
            logger.debug("Linked gatewayMsgId={} to operatorMsgId={}", smsid, smscid);
            return 1;
        }
        logger.warn("Invalid parameters: smsid={}, smscid={}", smsid, smscid);
        return 0;
    }

    @Override
    public String getHashedMessageID(String messageId) {
        //note in case of smppclient this method is overridden
        if (messageId == null || messageId.isEmpty()) {
            return "";
        }
        return SecurityUtils.generateMD5(outWorker.getType().concat(messageId));
    }

    @Override
    public String getVendorPriceGateway() {
        return "";
    }

    @Override
    public void createAndEnqueueDLR(int mqid, String smscid, String smsid, String from, String to,
                                    String body, int state, String errorCode, HashMap<String, String> tlvs) {
        Optional<MessageState> optState = outWorker.getWorkerResources().getDlrService().resolveAndRemoveDlr(smscid, state);

        if (optState.isPresent()) {
            MessageState msgState = optState.get();

            StandardMessage dlrMsg = new StandardMessage();
            dlrMsg.serial = msgState.getGatewayMsgId();
            dlrMsg.from = msgState.getDestAddr();
            dlrMsg.to = msgState.getSourceAddr();
            dlrMsg.body = body;
            dlrMsg.state = state;
            dlrMsg.errcode = errorCode != null ? errorCode : "";
            dlrMsg.systemId = msgState.getSystemId();
            dlrMsg.owner_id = msgState.getSystemId();
            dlrMsg.type = StandardMessage.MSG_DLR;
            try {
                outWorker.enqueueToRouter(dlrMsg);
            } catch (InterruptedException ie) {
                outWorker.handleException(ie);
            }
            logger.debug("DLR enqueued for gatewayMsgId: {}, status: {}", msgState.getGatewayMsgId(), state);
        } else {
            logger.warn("DLR received for unknown/expired message: smsid={}", smsid);
        }
    }

    @Override
    public int getConfiguredMccMnc() {
        return 0;
    }

    public StandardMessage pollDlr() {
        return dlrQueue.poll();
    }

    public int getDlrQueueSize() {
        return dlrQueue.size();
    }
}