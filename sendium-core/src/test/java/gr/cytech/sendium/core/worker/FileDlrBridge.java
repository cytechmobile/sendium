package gr.cytech.sendium.core.worker;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/** Test-only bridge to the operations the file adapter implements so far; others fail loudly. */
final class FileDlrBridge implements DlrStorageBackend {
    private final FileDlrStorage storage;

    FileDlrBridge(FileDlrStorage storage) {
        this.storage = storage;
    }

    @Override
    public String backend() {
        return "file";
    }

    @Override
    public void recordProviderAccepted(MessageState state, String providerName, String providerMessageId) {
        storage.recordProviderAccepted(state, providerName, providerMessageId);
    }

    @Override
    public Optional<MessageState> recordProviderRejected(MessageState state, String providerName,
                                                         String providerMessageId, int dlrState, String errorCode) {
        return storage.recordProviderRejected(state, providerName, providerMessageId, dlrState, errorCode);
    }

    @Override
    public Optional<MessageState> resolveDlr(String providerName, String providerMessageId,
                                             MessageState.MessageStatus status, int dlrState, String errorCode) {
        return storage.resolveDlr(providerName, providerMessageId, status, dlrState, errorCode);
    }

    @Override
    public Optional<MessageState> getState(String gatewayMsgId) {
        return storage.getState(gatewayMsgId);
    }

    @Override
    public List<ProviderDlrEvent.Result> applyProviderBatch(List<ProviderDlrEvent> events) {
        return storage.applyProviderBatch(events);
    }

    @Override
    public List<MessageState> listPendingSmppDeliveries(String systemId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<MessageState> claimDueHttpDeliveries(int limit) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Optional<MessageState> startDeliveryAttempt(String gatewayMsgId, MessageState.DeliveryChannel expectedChannel) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean completeDelivery(String gatewayMsgId, int expectedAttempt) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean retryDelivery(String gatewayMsgId, int expectedAttempt, String result, long nextAttemptAt) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean failDelivery(String gatewayMsgId, int expectedAttempt, String result) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean failInvalidDelivery(String gatewayMsgId, int expectedAttempt, String result) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Optional<ReceiptCounts> readMetricSnapshot(int maxRows, Duration timeout) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void verifyAvailable() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void close() {
        storage.close();
    }
}
