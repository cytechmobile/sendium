package gr.cytech.sendium.core.worker;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/** Internal managed operations, separate from the public receipt-processing contract. */
interface DlrStorageBackend extends DlrStorage, AutoCloseable {
    String backend();

    List<ProviderDlrEvent.Result> applyProviderBatch(List<ProviderDlrEvent> events);

    Optional<ReceiptCounts> readMetricSnapshot(int maxRows, Duration timeout);

    void verifyAvailable();

    @Override
    void close();

    record ReceiptCounts(long waitingProvider, long pendingReturn, long failedReturn) { }
}
