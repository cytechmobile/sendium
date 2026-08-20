package gr.cytech.sendium.core.smpp.server;

import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.worker.DlrService;
import gr.cytech.sendium.core.worker.DlrStorageException;
import gr.cytech.sendium.core.worker.MessageState;
import gr.cytech.sendium.util.MessageTrace;
import gr.cytech.sendium.util.SensitiveLogSanitizer;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
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
        boolean persisted = true;
        int start = 0;
        while (start < eventsQueue.size()) {
            boolean clientSubmission = isClientSubmission(eventsQueue.get(start));
            int end = start + 1;
            while (end < eventsQueue.size() && isClientSubmission(eventsQueue.get(end)) == clientSubmission) {
                end++;
            }
            persisted = persistBatch(eventsQueue.subList(start, end)) && persisted;
            start = end;
        }
        return CompletableFuture.completedFuture(persisted);
    }

    private boolean isClientSubmission(InEvent<StandardMessage> event) {
        return event != null && event.submitSm != null;
    }

    private boolean persistBatch(List<InEvent<StandardMessage>> eventsQueue) {
        if (eventsQueue.isEmpty()) {
            return true;
        }
        if (!isDlrPersistenceEnabled()) {
            worker.handlePersistedMessages(eventsQueue);
            return true;
        }
        List<MessageState> states = new ArrayList<>(eventsQueue.size());
        for (InEvent<StandardMessage> event : eventsQueue) {
            if (event == null) {
                continue;
            }
            StandardMessage msg = event.pMsg;
            if (msg != null) {
                MessageState state = new MessageState(msg.serial, msg.owner_id, msg.systemId, msg.from, msg.to, null);
                state.setReassembledParts(msg.reassembledParts);
                states.add(state);
            }
        }

        try {
            getDlrService().saveInitialStates(states);
        } catch (DlrStorageException e) {
            logger.error("SMPP submission batch rejected: DLR storage unavailable");
            worker.handleMessagePersistenceFailure(eventsQueue);
            return false;
        } catch (Exception e) {
            logger.error("Failed to persist SMPP submission batch", e);
            worker.handleMessagePersistenceFailure(eventsQueue);
            return false;
        }
        worker.handlePersistedMessages(eventsQueue);
        return true;
    }

    /**
     * Acknowledgement and router admission are always deferred to
     * {@link SmppServerWorker#handlePersistedMessages(List)}, so the worker must drain the ingress queue on shutdown
     * even when Sendium-owned DLR persistence is disabled and the persist step itself is a no-op.
     */
    @Override
    public boolean persistsBeforeAcknowledgement() {
        return true;
    }

    @Override
    public boolean markAsUnpushed(StandardMessage msg) {
        if (msg == null || msg.type != StandardMessage.MSG_DLR) {
            return false;
        }
        if (!isDlrPersistenceEnabled()) {
            //let the worker retry in memory, as documented on SmppServerMessageStore#markAsUnpushed
            return false;
        }

        try {
            boolean saved = getDlrService().saveUnpushedDlr(msg);
            if (!saved) {
                logger.warn("Failed to save unpushed DLR {}", MessageTrace.identifiers(msg));
            }
            return saved;
        } catch (Exception e) {
            logger.warn("Exception while saving unpushed DLR {}", MessageTrace.identifiers(msg), e);
            return false;
        }
    }

    @Override
    public void onClientConnected(String systemId) {
        if (!isDlrPersistenceEnabled()) {
            return;
        }

        DlrService dlrService = getDlrService();
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
                logger.warn("Failed to re-enqueue unpushed DLR {}", MessageTrace.identifiers(msg), e);
            }
        }
    }

    private boolean isDlrPersistenceEnabled() {
        return worker.getWorkerResources().isDlrPersistenceEnabled();
    }

    private DlrService getDlrService() {
        return worker.getWorkerResources().getDlrService();
    }

    @Override
    public int getMaxAttempts(boolean isDlr) {
        return worker != null ? worker.getMaxRetries() : 3;
    }

    @Override
    public void configure(String key, String newValue, String oldValue) {
        logger.debug("Configure: key={}, newValue={}, oldValue={}",
                key,
                SensitiveLogSanitizer.maskValue(key, newValue),
                SensitiveLogSanitizer.maskValue(key, oldValue));
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
