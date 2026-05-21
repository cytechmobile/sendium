package gr.cytech.sendium.core.smpp.server;

import gr.cytech.sendium.core.message.StandardMessage;

import java.util.List;
import java.util.concurrent.Future;

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
     * Handle incoming messages (e.g., save to DB or route directly).
     */
    Future<Boolean> persistMessages(List<InEvent<M>> eventsQueue);

    /**
     * Mark a message as unpushed to retry it later.
     * @return true if successfully handled by the store, false if the worker should handle the retry in-memory.
     */
    boolean markAsUnpushed(M msg);

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
