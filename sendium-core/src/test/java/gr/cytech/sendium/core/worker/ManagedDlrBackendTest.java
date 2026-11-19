package gr.cytech.sendium.core.worker;

import gr.cytech.sendium.core.message.StandardMessage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ManagedDlrBackendTest {
    @Test
    @Timeout(10)
    void batchResultsWaitForBackendPublication() throws Exception {
        DlrStorageBackend backend = mock(DlrStorageBackend.class);
        when(backend.backend()).thenReturn("test");
        MessageState state = new MessageState("gateway", "account", "system", "from", "to", "https://callback.test");
        List<ProviderDlrEvent> events = List.of(
                new ProviderDlrEvent.Accepted(state, "provider", "id"),
                new ProviderDlrEvent.Receipt("provider", "id", MessageState.MessageStatus.DELIVERED,
                        StandardMessage.DLR_STAT_DELIVRD, "0"));
        List<ProviderDlrEvent.Result> results = List.of(
                ProviderDlrEvent.Result.success(Optional.empty()),
                ProviderDlrEvent.Result.success(Optional.of(state)));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(backend.applyProviderBatch(events)).thenAnswer(call -> {
            entered.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return results;
        });

        var metrics = new SimpleMeterRegistry();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var storage = storage(backend, metrics);

            var completion = executor.submit(() -> storage.applyProviderBatch(events));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(completion.isDone()).isFalse();
                assertThat(metrics.find("sendium.dlr.outcomes").counter()).isNull();
                assertThat(metrics.find("sendium.dlr.storage.operation").timer()).isNull();
            } finally {
                release.countDown();
            }
            assertThat(completion.get(5, TimeUnit.SECONDS)).containsExactlyElementsOf(results);
            assertThat(storage.backend()).isEqualTo("test");
            assertThat(metrics.get("sendium.dlr.outcomes")
                    .tag("provider", "provider").tag("outcome", "delivered").counter().count()).isEqualTo(1);
            assertThat(metrics.get("sendium.dlr.storage.operation")
                    .tag("backend", "test").tag("operation", "provider_batch").tag("outcome", "success")
                    .timer().count()).isEqualTo(1);
            verify(backend).applyProviderBatch(events);
        } finally {
            metrics.close();
        }
    }

    @Test
    void managementOperationsUseTheSelectedBackend() throws Exception {
        DlrStorageBackend backend = mock(DlrStorageBackend.class);
        when(backend.backend()).thenReturn("test");
        var counts = new DlrStorageBackend.ReceiptCounts(1, 2, 3);
        when(backend.readMetricSnapshot(100, Duration.ofSeconds(2))).thenReturn(Optional.of(counts));
        var metrics = new SimpleMeterRegistry();
        try {
            var storage = storage(backend, metrics);

            storage.verifyAvailable();
            assertThat(storage.readMetricSnapshot(100, Duration.ofSeconds(2))).contains(counts);
            storage.close();

            verify(backend).verifyAvailable();
            verify(backend).readMetricSnapshot(100, Duration.ofSeconds(2));
            verify(backend).close();
        } finally {
            metrics.close();
        }
    }

    private ManagedDlrStorage storage(DlrStorageBackend backend, SimpleMeterRegistry metrics) throws Exception {
        var storage = new ManagedDlrStorage();
        storage.meterRegistry = metrics;
        // Exercise a non-PostgreSQL delegate without adding a production setter only for tests.
        Field delegate = ManagedDlrStorage.class.getDeclaredField("delegate");
        delegate.setAccessible(true);
        delegate.set(storage, backend);
        return storage;
    }
}
