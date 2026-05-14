package gr.cytech.sendium.core.smpp.server;

import gr.cytech.sendium.core.message.StandardMessage;
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
                    String systemId = msg.systemId;
                    String sourceAddr = msg.from;
                    String destAddr = msg.to;

                    MessageState state = new MessageState(gatewayMsgId, systemId, sourceAddr, destAddr, null);
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
        logger.info("markAsUnpushed message {}", msg);
        return true;
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