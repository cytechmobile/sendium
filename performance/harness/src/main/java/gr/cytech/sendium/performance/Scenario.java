package gr.cytech.sendium.performance;

/** Protocol adapters own traffic scheduling and keep asynchronous processing alive during drain. */
public interface Scenario extends AutoCloseable {
    void awaitReady() throws Exception;
    void startGeneration(Lifecycle.Phase phase, long startNanos, long deadlineNanos) throws Exception;
    void stopGeneration() throws Exception;
    boolean hasOutstandingWork() throws Exception;
    void finalizeAccounting() throws Exception;
    @Override
    void close() throws Exception;
}
