package gr.cytech.sendium.core.smpp.server;

import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.worker.InMemoryDlrService;
import gr.cytech.sendium.core.worker.MessageState;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

public class InMemorySmppServerMessageStore implements SmppServerMessageStore<StandardMessage> {
    private static final Logger logger = LoggerFactory.getLogger(InMemorySmppServerMessageStore.class);

    private final SmppServerWorker<StandardMessage> worker;

    @Inject
    public InMemorySmppServerMessageStore(SmppServerWorker<StandardMessage> worker) {
        this.worker = worker;
    }

    @Override
    public void start() {
        logger.info("InMemorySmppServerMessageStore started");
    }

    @Override
    public void stop() {
        logger.info("InMemorySmppServerMessageStore stopped");
    }

    @Override
    public Future<Boolean> persistMessages(List<InEvent<StandardMessage>> eventsQueue) {
        for (InEvent<StandardMessage> event : eventsQueue) {
            try {
                StandardMessage msg = event.pMsg;
                if (msg != null) {
                    String gatewayMsgId = msg.serial;
                    String accountId = msg.owner_id;
                    String systemId = msg.systemId;
                    String sourceAddr = msg.from;
                    String destAddr = msg.to;

                    MessageState state = new MessageState(gatewayMsgId, accountId, systemId, sourceAddr, destAddr, null);
                    worker.getWorkerResources().getDlrService().saveInitialState(state);
                }
            } catch (Exception e) {
                logger.error("Failed to persist message state", e);
            }
        }
        return CompletableFuture.completedFuture(true);
    }

    @Override
    public boolean markAsUnpushed(StandardMessage msg) {
        if (msg == null || msg.type != StandardMessage.MSG_DLR) {
            return false;
        }

        try {
            boolean saved = getDlrService().saveUnpushedDlr(msg);
            if (!saved) {
                logger.warn("Failed to save unpushed DLR: {}", msg);
            }
            return saved;
        } catch (Exception e) {
            logger.warn("Exception while saving unpushed DLR: {}", msg, e);
            return false;
        }
    }

    @Override
    public void onClientConnected(String systemId) {
        InMemoryDlrService dlrService = getDlrService();
        List<StandardMessage> unpushedDlrs = dlrService.claimUnpushedDlrs(systemId);
        if (unpushedDlrs.isEmpty()) {
            logger.info("Unpushed DLR(s) not found for systemId:{}", systemId);
            return;
        }

        logger.info("Re-enqueuing {} unpushed DLR(s) for systemId:{}", unpushedDlrs.size(), systemId);
        for (StandardMessage msg : unpushedDlrs) {
            try {
                if (worker.enqueueNoExceptions(msg)) {
                    dlrService.removeUnpushedDlr(msg);
                } else {
                    dlrService.releaseUnpushedDlrClaim(msg);
                }
            } catch (Exception e) {
                dlrService.releaseUnpushedDlrClaim(msg);
                logger.warn("Failed to re-enqueue unpushed DLR: {}", msg, e);
            }
        }
    }

    private InMemoryDlrService getDlrService() {
        return worker.getWorkerResources().getDlrService();
    }

    @Override
    public int getMaxAttempts(boolean isDlr) {
        return worker != null ? worker.getMaxRetries() : 3;
    }

    @Override
    public void configure(String key, String newValue, String oldValue) {
        logger.debug("Configure: key={}, newValue={}, oldValue={}", key, newValue, oldValue);
    }

    @Override
    public int getInsertBatchSize() {
        return 100;
    }

    @Override
    public long getInsertBatchPeriod() {
        return 100;
    }
}
