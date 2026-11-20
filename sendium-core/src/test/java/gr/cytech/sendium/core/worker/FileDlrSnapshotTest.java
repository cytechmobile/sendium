package gr.cytech.sendium.core.worker;

import gr.cytech.sendium.core.worker.FileDlrTestSupport.MutableClock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.time.Duration;

import static gr.cytech.sendium.core.worker.FileDlrTestSupport.raw;
import static gr.cytech.sendium.core.worker.FileDlrTestSupport.seed;
import static gr.cytech.sendium.core.worker.FileDlrTestSupport.single;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileDlrSnapshotTest {
    @TempDir
    Path root;

    @Test
    void overBudgetSnapshotsAreUnavailableWithoutMutation() throws Exception {
        var clock = new MutableClock();
        FileDlrStorage.open(root, Duration.ofMinutes(5), clock).close();
        seed(root, 10_000, "HTTP", "PENDING", "system", 0);
        seed(root, 2, "SMPP", "FAILED", "system", 0);
        seed(root, 1, "SMPP", "WAITING_PROVIDER", "system", 0);

        try (var storage = FileDlrStorage.open(root, Duration.ofMinutes(5), clock)) {
            // Long past retention: a snapshot must not run cleanup.
            clock.set(Duration.ofDays(365).toMillis());
            assertThat(storage.readMetricSnapshot(10_003, Duration.ofSeconds(5)))
                    .contains(new DlrStorageBackend.ReceiptCounts(1, 10_000, 2));
            assertThat(storage.readMetricSnapshot(10_002, Duration.ofSeconds(5))).isEmpty();

            // An already expired deadline interrupts the query without latching the store.
            assertThat(storage.readMetricSnapshot(20_000, System.nanoTime() - 1)).isEmpty();
            assertThat(storage.readMetricSnapshot(20_000, Duration.ofSeconds(5)))
                    .contains(new DlrStorageBackend.ReceiptCounts(1, 10_000, 2));
            storage.verifyAvailable();
            assertThat(storage.backend()).isEqualTo("file");

            assertThatThrownBy(() -> storage.readMetricSnapshot(0, Duration.ofSeconds(1)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> storage.readMetricSnapshot(1, Duration.ofMillis(1_500)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        try (Connection connection = raw(root)) {
            assertThat(single(connection, "SELECT count(*) FROM dlr_message")).isEqualTo("10003");
        }
    }
}
