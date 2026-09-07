package gr.cytech.sendium.core.smpp.server;

import gr.cytech.sendium.core.message.StandardMessage;

import java.util.List;
import java.util.OptionalInt;

public interface SmppServerMessageStore<M extends StandardMessage> {

    /**
     * Start any internal threads (like DB polling or batch executors).
     */
    void start();

    /**
     * Stop internal threads and clean up resources.
     */
    void stop();

    /**
     * Process accepted incoming messages.
     */
    void processIngressMessages(List<InEvent<M>> eventsQueue);

    /**
     * Mark a message as unpushed to retry it later.
     * @return true if successfully handled by the store, false if the worker should handle the retry in-memory.
     */
    boolean markAsUnpushed(M msg);

    /**
     * Whether this store keeps DLR rows until the downstream client acknowledges them.
     */
    default boolean tracksDlrDeliveryAttempts() {
        return false;
    }

    /**
     * Starts one logical DLR delivery attempt. The default keeps existing stores source-compatible.
     */
    default OptionalInt startDlrDeliveryAttempt(M msg) {
        return OptionalInt.empty();
    }

    /**
     * Completes a logical DLR delivery attempt after all receipt parts were acknowledged.
     */
    default boolean completeDlrDeliveryAttempt(M msg, int attempt) {
        return true;
    }

    /**
     * Releases a failed logical DLR delivery attempt for a later replay.
     */
    default boolean releaseDlrDeliveryAttempt(M msg, int attempt, String result) {
        return markAsUnpushed(msg);
    }

    /**
     * Called when a transmittable SMPP client session becomes available again.
     */
    default void onClientConnected(String systemId) {
    }

    /**
     * Fetch the maximum allowed attempts for a message.
     */
    int getMaxAttempts(boolean isDlr);

    void configure(String key, String newValue, String oldValue);

    int getInsertBatchSize();

    long getInsertBatchPeriod();
}
