package gr.cytech.sendium.core.worker;

import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.storage.OutboundStage;
import gr.cytech.sendium.core.storage.OutboundStorageException;

import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/** Transport-neutral execution boundary for lifecycle-owned outbound work. */
public interface OutboundProvider<M extends StandardMessage> {
    String getFullName();

    boolean isKeepOnRunning();

    boolean isPause();

    int getThreadCount();

    /** Applies worker preparation and returns ownership of the request plus its required tracking handoffs. */
    Submission submitPrepared(M message, Retry<M> retry, boolean retryAttempt) throws Exception;

    /** Runs the owning lifecycle's guard after preparation filters and before provider effects. */
    default Submission submitPrepared(M message, Retry<M> retry, boolean retryAttempt, Consumer<? super M> beforeSend)
            throws Exception {
        throw new OutboundStorageException(OutboundStage.Role.ROUTED_WORK, OutboundStorageException.Reason.UNSUPPORTED,
                "dlrProvider|error|reason:providerDoesNotProtectPreparation");
    }

    @FunctionalInterface
    interface Retry<M extends StandardMessage> {
        CompletionStage<Void> retry(M message, boolean router);
    }

    /**
     * Completion includes required tracking handoffs. A retryable handoff failure leaves completion pending;
     * retryPendingHandoffs resumes that handoff, never an already completed provider request.
     */
    interface Submission {
        CompletionStage<Void> completion();

        void retryPendingHandoffs();
    }
}
