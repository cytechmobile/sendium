package gr.cytech.sendium.core.smpp.server;

import gr.cytech.sendium.core.message.StandardMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;

/**
 * Tracks one durable SMPP DLR attempt across all generated deliver_sm parts.
 */
public final class DlrDeliveryBatch<M extends StandardMessage> {
    public static final String COMPLETION_STORAGE_ERROR = "completion_storage_error";
    private static final Logger logger = LoggerFactory.getLogger(DlrDeliveryBatch.class);
    private static final Set<String> FAILURE_RESULTS = Set.of(
            COMPLETION_STORAGE_ERROR,
            "enqueue_failed",
            "generic_nack",
            "non_ok_response",
            "send_failed",
            "session_closed",
            "timeout",
            "wrong_response");

    private final M message;
    private final int attempt;
    private final Set<Integer> expectedParts;
    private final Set<Integer> successfulParts = new HashSet<>();
    private final SmppServerMessageStore<M> messageStore;
    private final SmppServerSessionHandler<?> owner;
    private boolean active = true;

    public DlrDeliveryBatch(M message, int attempt, Set<Integer> expectedParts,
                            SmppServerMessageStore<M> messageStore, SmppServerSessionHandler<?> owner) {
        if (message == null || message.serial == null || message.serial.isBlank()) {
            throw new IllegalArgumentException("DLR batch requires a gateway message ID");
        }
        if (expectedParts == null || expectedParts.isEmpty()) {
            throw new IllegalArgumentException("DLR batch requires at least one part");
        }
        this.message = message;
        this.attempt = attempt;
        this.expectedParts = Set.copyOf(expectedParts);
        this.messageStore = messageStore;
        this.owner = owner;
    }

    public synchronized boolean isActive() {
        return active;
    }

    public int getAttempt() {
        return attempt;
    }

    public String getGatewayMessageId() {
        return message.serial;
    }

    public void partSucceeded(int partOrdinal) {
        boolean complete = false;
        synchronized (this) {
            if (!active || !expectedParts.contains(partOrdinal) || !successfulParts.add(partOrdinal)) {
                return;
            }
            if (successfulParts.size() == expectedParts.size()) {
                active = false;
                complete = true;
            }
        }
        if (complete) {
            completeDelivery();
        }
    }

    public void fail(String result) {
        synchronized (this) {
            if (!active) {
                return;
            }
            active = false;
        }
        release(normalizeResult(result));
        unregister();
    }

    private void completeDelivery() {
        boolean completed = false;
        try {
            completed = messageStore.completeDlrDeliveryAttempt(message, attempt);
        } catch (RuntimeException e) {
            logger.error("SMPP DLR completion storage error gatewayMsgId={} attempt={}",
                    message.serial, attempt, e);
        }
        if (completed) {
            logger.info("SMPP DLR delivery completed gatewayMsgId={} attempt={}", message.serial, attempt);
        } else {
            release(COMPLETION_STORAGE_ERROR);
        }
        unregister();
    }

    private void release(String result) {
        try {
            if (!messageStore.releaseDlrDeliveryAttempt(message, attempt, result)) {
                logger.error("SMPP DLR attempt release was not applied gatewayMsgId={} attempt={} result={}",
                        message.serial, attempt, result);
            } else {
                logger.warn("SMPP DLR delivery attempt failed gatewayMsgId={} attempt={} result={}",
                        message.serial, attempt, result);
            }
        } catch (RuntimeException e) {
            logger.error("SMPP DLR attempt release failed gatewayMsgId={} attempt={} result={}",
                    message.serial, attempt, result, e);
        }
    }

    private void unregister() {
        if (owner != null) {
            owner.unregisterDlrBatch(this);
        }
    }

    private String normalizeResult(String result) {
        return FAILURE_RESULTS.contains(result) ? result : "send_failed";
    }
}
