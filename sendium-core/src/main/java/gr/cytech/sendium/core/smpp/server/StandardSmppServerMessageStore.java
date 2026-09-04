package gr.cytech.sendium.core.smpp.server;

import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.worker.DlrService;
import gr.cytech.sendium.core.worker.DlrStorageException;
import gr.cytech.sendium.core.worker.MessageState;
import gr.cytech.sendium.util.SensitiveLogSanitizer;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

public class StandardSmppServerMessageStore implements SmppServerMessageStore<StandardMessage> {
    private static final Logger logger = LoggerFactory.getLogger(StandardSmppServerMessageStore.class);

    private final SmppServerWorker<StandardMessage> worker;

    @Inject
    public StandardSmppServerMessageStore(SmppServerWorker<StandardMessage> worker) {
        this.worker = worker;
    }

    @Override
    public void start() {
        logger.info("StandardSmppServerMessageStore started");
    }

    @Override
    public void stop() {
        logger.info("StandardSmppServerMessageStore stopped");
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
                state.setDeliveryChannel(msg.acked && worker.isForwardDlrs() ?
                        MessageState.DeliveryChannel.SMPP
                        : MessageState.DeliveryChannel.NONE);
                state.setReassembledParts(msg.reassembledParts);
                states.add(state);
            }
        }

        try {
            getDlrService().saveInitialStates(states);
        } catch (DlrStorageException e) {
            logger.error("Failed to persist accepted SMPP submission batch: DLR storage unavailable");
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

    @Override
    public boolean persistsMultipartPartsBeforeAssembly() {
        return true;
    }

    @Override
    public boolean markAsUnpushed(StandardMessage msg) {
        return msg != null && msg.type == StandardMessage.MSG_DLR && isDlrPersistenceEnabled();
    }

    @Override
    public boolean tracksDlrDeliveryAttempts() {
        return isDlrPersistenceEnabled();
    }

    @Override
    public OptionalInt startDlrDeliveryAttempt(StandardMessage msg) {
        if (!isDlrPersistenceEnabled()) {
            return SmppServerMessageStore.super.startDlrDeliveryAttempt(msg);
        }
        return getDlrService().startDeliveryAttempt(msg.serial, MessageState.DeliveryChannel.SMPP)
                .map(state -> OptionalInt.of(state.getDeliveryAttemptCount()))
                .orElseGet(OptionalInt::empty);
    }

    @Override
    public boolean completeDlrDeliveryAttempt(StandardMessage msg, int attempt) {
        return !isDlrPersistenceEnabled() || getDlrService().completeDelivery(msg.serial, attempt);
    }

    @Override
    public boolean releaseDlrDeliveryAttempt(StandardMessage msg, int attempt, String result) {
        return !isDlrPersistenceEnabled() ||
                getDlrService().retryDelivery(msg.serial, attempt, result, System.currentTimeMillis());
    }

    @Override
    public void onClientConnected(String systemId) {
        if (!isDlrPersistenceEnabled()) {
            return;
        }
        List<MessageState> pending = getDlrService().listPendingSmppDeliveries(systemId);
        for (MessageState state : pending) {
            StandardMessage dlr = toDlrMessage(state);
            try {
                worker.enqueue(dlr);
                logger.info("SMPP DLR replay enqueued gatewayMsgId={}", state.getGatewayMsgId());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.warn("SMPP DLR replay enqueue interrupted gatewayMsgId={}", state.getGatewayMsgId());
                return;
            } catch (RuntimeException e) {
                logger.warn("SMPP DLR replay enqueue failed gatewayMsgId={}", state.getGatewayMsgId(), e);
            }
        }
    }

    private StandardMessage toDlrMessage(MessageState state) {
        StandardMessage dlr = new StandardMessage();
        dlr.serial = state.getGatewayMsgId();
        dlr.from = state.getDestAddr();
        dlr.to = state.getSourceAddr();
        dlr.state = state.getDlrState();
        dlr.errcode = state.getErrorCode();
        dlr.systemId = state.getSystemId();
        dlr.owner_id = state.getAccountId();
        List<String> reassembledParts = state.getReassembledParts();
        dlr.reassembledParts = reassembledParts == null ? null : new ArrayList<>(reassembledParts);
        dlr.type = StandardMessage.MSG_DLR;
        return dlr;
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
