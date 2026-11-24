package gr.cytech.sendium.core.worker;

import gr.cytech.sendium.core.message.StandardMessage;

import java.util.concurrent.CompletionStage;

/** Transport-neutral execution boundary for lifecycle-owned outbound work. */
public interface OutboundProvider<M extends StandardMessage> {
    String getFullName();

    boolean isKeepOnRunning();

    boolean isPause();

    int getThreadCount();

    /** Applies worker preparation and returns ownership of the request plus its required tracking handoffs. */
    Submission submitPrepared(M message, Retry<M> retry, boolean retryAttempt) throws Exception;

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
