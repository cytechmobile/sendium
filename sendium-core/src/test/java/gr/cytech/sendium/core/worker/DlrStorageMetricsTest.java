package gr.cytech.sendium.core.worker;

import io.micrometer.core.instrument.MockClock;
import io.micrometer.core.instrument.simple.SimpleConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;

class DlrStorageMetricsTest {
    private final MockClock clock = new MockClock();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry(SimpleConfig.DEFAULT, clock);
    private final ManagedDlrStorage storage = mock(ManagedDlrStorage.class);

    @AfterEach
    void close() {
        registry.close();
    }

    @Test
    void onlyCompleteSnapshotsAreAvailableAndScrapesNeverReadStorage() {
        var collector = collector(true);
        assertThat(metadata("enabled")).isEqualTo(1);
        assertThat(metadata("available")).isZero();
        assertThat(occupancy("waiting_provider")).isNaN();
        assertThat(metadata("last.success.timestamp.seconds")).isNaN();
        when(storage.readMetricSnapshot(100000, Duration.ofSeconds(2)))
                .thenReturn(Optional.of(new PostgresqlDlrStorage.ReceiptCounts(12, 3, 1)));
        collector.collect();
        clearInvocations(storage);
        for (int scrape = 0; scrape < 10; scrape++) {
            assertThat(metadata("available")).isEqualTo(1);
            assertThat(occupancy("waiting_provider")).isEqualTo(12);
            assertThat(occupancy("pending_return")).isEqualTo(3);
            assertThat(occupancy("failed_return")).isEqualTo(1);
        }
        verifyNoInteractions(storage);
    }

    @Test
    void emptyStoreIsZeroButDisabledCollectionIsUnavailableAndDoesNoIo() {
        var collector = collector(false);
        collector.collect();
        assertThat(metadata("enabled")).isZero();
        assertThat(metadata("available")).isZero();
        assertThat(occupancy("pending_return")).isNaN();
        verifyNoInteractions(storage);
        // Clear meter identities before binding an independent collector to this test registry.
        registry.clear();
        collector = collector(true);
        when(storage.readMetricSnapshot(anyInt(), any())).thenReturn(Optional.of(new PostgresqlDlrStorage.ReceiptCounts(0, 0, 0)));
        collector.collect();
        assertThat(metadata("available")).isEqualTo(1);
        assertThat(occupancy("pending_return")).isZero();
    }

    @Test
    void failureOverBudgetAndStalenessInvalidateValuesButRetainLastSuccessTime() {
        var collector = collector(true);
        when(storage.readMetricSnapshot(anyInt(), any()))
                .thenReturn(Optional.of(new PostgresqlDlrStorage.ReceiptCounts(1, 2, 3)))
                .thenReturn(Optional.empty())
                .thenThrow(new DlrStorageException("timeout"))
                .thenReturn(Optional.of(new PostgresqlDlrStorage.ReceiptCounts(4, 5, 6)));
        collector.collect();
        double firstSuccess = metadata("last.success.timestamp.seconds");
        clock.add(Duration.ofSeconds(1));
        collector.collect();
        assertThat(metadata("available")).isZero();
        assertThat(occupancy("waiting_provider")).isNaN();
        assertThat(metadata("last.success.timestamp.seconds")).isEqualTo(firstSuccess);
        collector.collect();
        assertThat(metadata("available")).isZero();
        collector.collect();
        assertThat(occupancy("waiting_provider")).isEqualTo(4);
        assertThat(metadata("last.success.timestamp.seconds")).isGreaterThan(firstSuccess);
        clock.add(Duration.ofSeconds(120));
        assertThat(metadata("available")).isZero();
        assertThat(occupancy("waiting_provider")).isNaN();
    }

    @Test
    @Timeout(10)
    void overlappingCollectionIsSkippedRatherThanQueuingAnotherDatabaseRead() throws Exception {
        var collector = collector(true);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(storage.readMetricSnapshot(anyInt(), any())).thenAnswer(call -> {
            entered.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return Optional.of(new PostgresqlDlrStorage.ReceiptCounts(1, 0, 0));
        });
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(collector::collect);
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                collector.collect();
                verify(storage, times(1)).readMetricSnapshot(anyInt(), any());
            } finally {
                release.countDown();
            }
            first.get(5, TimeUnit.SECONDS);
            assertThat(metadata("available")).isEqualTo(1);
        }
    }

    @Test
    void collectorReplacementUnregistersOnlyItsOwnedMetersBeforeBindingFreshState() {
        var first = collector(true);
        registry.counter("unrelated.counter").increment();
        when(storage.readMetricSnapshot(anyInt(), any()))
                .thenReturn(Optional.of(new PostgresqlDlrStorage.ReceiptCounts(1, 0, 0)))
                .thenReturn(Optional.of(new PostgresqlDlrStorage.ReceiptCounts(7, 0, 0)));
        first.collect();
        assertThat(occupancy("waiting_provider")).isEqualTo(1);
        first.unregister();
        first.unregister();
        assertThat(registry.find("sendium.dlr.storage.messages").gauges()).isEmpty();
        assertThat(registry.get("unrelated.counter").counter().count()).isEqualTo(1);
        var replacement = collector(true);
        assertThat(metadata("available")).isZero();
        assertThat(occupancy("waiting_provider")).isNaN();
        replacement.collect();
        assertThat(occupancy("waiting_provider")).isEqualTo(7);
    }

    @Test
    void rejectsInvalidBudgetsWithoutDatabaseAccess() {
        for (int invalid : new int[]{0, -1, Integer.MAX_VALUE}) {
            var collector = new DlrStorageMetrics();
            collector.metrics = registry;
            collector.storage = storage;
            collector.maxRows = invalid;
            assertThatThrownBy(collector::initialize).isInstanceOf(IllegalArgumentException.class);
        }
        var collector = new DlrStorageMetrics();
        collector.metrics = registry;
        collector.storage = storage;
        collector.timeout = Duration.ofMillis(500);
        assertThatThrownBy(collector::initialize).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(storage);
    }

    private DlrStorageMetrics collector(boolean enabled) {
        var collector = new DlrStorageMetrics();
        collector.storage = storage;
        collector.metrics = registry;
        collector.enabled = enabled;
        collector.initialize();
        return collector;
    }

    private double occupancy(String state) {
        return registry.get("sendium.dlr.storage.messages").tag("state", state).gauge().value();
    }

    private double metadata(String suffix) {
        return registry.get("sendium.storage.collection." + suffix).tag("backend", "postgresql").tag("stage", "dlr").gauge().value();
    }
}
