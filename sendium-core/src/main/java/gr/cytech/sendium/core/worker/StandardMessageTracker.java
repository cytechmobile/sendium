package gr.cytech.sendium.core.worker;

import gr.cytech.sendium.core.AbstractOutWorker;
import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.util.MessageTrace;
import gr.cytech.sendium.util.SecurityUtils;
import gr.cytech.sendium.util.SensitiveLogSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Optional;

public class StandardMessageTracker implements Tracker<StandardMessage> {

    private static final Logger logger = LoggerFactory.getLogger(StandardMessageTracker.class);
    AbstractOutWorker<StandardMessage> outWorker;

    public StandardMessageTracker(AbstractOutWorker<StandardMessage> worker) {
        this.outWorker = worker;
    }

    @Override
    public void init() {
        logger.info("StandardMessageTracker initialized");
    }

    @Override
    public boolean stop() {
        logger.info("StandardMessageTracker stopping");
        return true;
    }

    @Override
    public void configure(String key, String newValue, String oldValue) {
        logger.debug("Configure: key={}, newValue={}, oldValue={}",
                key,
                SensitiveLogSanitizer.maskValue(key, newValue),
                SensitiveLogSanitizer.maskValue(key, oldValue));
    }

    @Override
    public int updateSendStatusAndExtID(String hashedProviderMessageId, StandardMessage message,
                                        String providerMessageId) {
        String gatewayMessageId = message.serial;
        String providerName = outWorker.getDlrProviderName();
        if (gatewayMessageId == null || gatewayMessageId.isBlank() ||
                providerName == null || providerName.isBlank() ||
                providerMessageId == null || providerMessageId.isBlank()) {
            logger.warn("Invalid DLR correlation identifiers");
            return 0;
        }
        if (!outWorker.getWorkerResources().isDlrPersistenceEnabled()) {
            return 0;
        }

        outWorker.getWorkerResources().getDlrService()
                .linkProviderMessageId(gatewayMessageId, providerName, providerMessageId);
        if (MessageTrace.shouldLog(outWorker.getConfigurationProvider(), MessageTrace.EVENT_PROVIDER_LINKED)) {
            logger.info("message.provider.linked providerMessageId={} {}", MessageTrace.value(providerMessageId),
                    MessageTrace.identifiers(message));
        }
        return 1;
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
    public void createAndEnqueueDLR(int mqid, String providerMessageId, String hashedProviderMessageId,
                                    String from, String to,
                                    String body, int state, String errorCode, HashMap<String, String> tlvs) {
        if (!outWorker.getWorkerResources().isDlrPersistenceEnabled()) {
            return;
        }

        Optional<MessageState> optState = outWorker.getWorkerResources().getDlrService()
                .resolveDlr(outWorker.getDlrProviderName(), providerMessageId, state, errorCode);

        if (optState.isPresent()) {
            MessageState msgState = optState.get();
            if (msgState.getDeliveryChannel() != MessageState.DeliveryChannel.SMPP) {
                return;
            }

            StandardMessage dlrMsg = new StandardMessage();
            dlrMsg.serial = msgState.getGatewayMsgId();
            dlrMsg.from = msgState.getDestAddr();
            dlrMsg.to = msgState.getSourceAddr();
            dlrMsg.body = body;
            dlrMsg.state = state;
            dlrMsg.errcode = errorCode != null ? errorCode : "";
            dlrMsg.systemId = msgState.getSystemId();
            dlrMsg.owner_id = msgState.getAccountId();
            var reassembledParts = msgState.getReassembledParts();
            dlrMsg.reassembledParts = reassembledParts == null ? null : new ArrayList<>(reassembledParts);
            dlrMsg.type = StandardMessage.MSG_DLR;
            try {
                outWorker.enqueueToRouter(dlrMsg);
            } catch (InterruptedException ie) {
                outWorker.handleException(ie);
            }
            if (MessageTrace.shouldLog(outWorker.getConfigurationProvider(), MessageTrace.EVENT_DLR)) {
                logger.info("message.dlr status={} providerMessageId={} {}", state,
                        MessageTrace.value(providerMessageId), MessageTrace.identifiers(dlrMsg));
            }
        } else {
            logger.warn("DLR received for unknown/expired provider message ID");
        }
    }

    @Override
    public int getConfiguredMccMnc() {
        return 0;
    }

}
