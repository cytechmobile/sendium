package gr.cytech.sendium.core.worker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Statement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static gr.cytech.sendium.core.worker.FileDlrTestSupport.httpState;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileDlrPublicationTest {
    @TempDir
    Path root;

    @Test
    void prePublicationFailureRestoresCommittedView() {
        MessageState committed = httpState();
        MessageState failed = httpState();
        try (var storage = FileDlrStorage.open(root)) {
            storage.recordProviderAccepted(committed, "P", "X");
            // The transfer to the new owner is fully applied before a constraint violation aborts it.
            storage.beforeCommit = c -> {
                try (Statement statement = c.createStatement()) {
                    statement.executeUpdate("UPDATE dlr_message SET dlr_mask = 9");
                }
            };
            assertThatThrownBy(() -> storage.recordProviderAccepted(failed, "P", "X"))
                    .isInstanceOf(DlrStorageException.class)
                    .hasMessageContaining("reason:constraint");
            storage.beforeCommit = c -> { };

            assertThat(storage.correlationOwner("P", "X")).contains(committed.getGatewayMsgId());
            assertThat(storage.getState(failed.getGatewayMsgId())).isEmpty();
            assertThat(storage.getState(committed.getGatewayMsgId()).orElseThrow().getProviderMessageId()).isEqualTo("X");
            MessageState next = httpState();
            storage.recordProviderAccepted(next, "P", "Z");
            assertThat(storage.correlationOwner("P", "Z")).contains(next.getGatewayMsgId());
        }
    }

    @Test
    void uncertainCommitLatchesUnavailable() {
        // Commit fails because the connection is gone mid-transaction.
        MessageState lost = httpState();
        try (var storage = FileDlrStorage.open(root)) {
            storage.beforeCommit = c -> c.close();
            assertThatThrownBy(() -> storage.recordProviderAccepted(lost, "P", "X"))
                    .isInstanceOf(DlrStorageException.class);
            assertUnavailable(storage);
        }
        try (var reopened = FileDlrStorage.open(root)) {
            assertThat(reopened.getState(lost.getGatewayMsgId())).isEmpty();
            // A real SQLITE_FULL inside the transaction also disables the adapter.
            MessageState full = httpState();
            reopened.beforeCommit = c -> {
                try (Statement statement = c.createStatement()) {
                    statement.execute("PRAGMA max_page_count = " + FileDlrTestSupport.single(c, "PRAGMA page_count"));
                    statement.executeUpdate("INSERT INTO dlr_meta VALUES ('fill', printf('%.*c', 2000000, 'x'))");
                }
            };
            assertThatThrownBy(() -> reopened.recordProviderAccepted(full, "P", "F"))
                    .isInstanceOf(DlrStorageException.class)
                    .hasMessageContaining("sqliteCode13");
            assertUnavailable(reopened);
        }
        try (var reopened = FileDlrStorage.open(root)) {
            assertThat(reopened.correlationOwner("P", "F")).isEmpty();
        }
    }

    @Test
    @Timeout(10)
    void readersWaitForCompletePublication() throws Exception {
        MessageState accepted = httpState();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var storage = FileDlrStorage.open(root);
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            storage.beforeCommit = c -> {
                entered.countDown();
                await(release);
            };
            var writer = executor.submit(() -> storage.recordProviderAccepted(accepted, "P", "X"));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            var reader = executor.submit(() -> storage.getState(accepted.getGatewayMsgId()));
            Thread.sleep(200);
            assertThat(reader.isDone()).isFalse();
            release.countDown();
            writer.get(5, TimeUnit.SECONDS);
            assertThat(reader.get(5, TimeUnit.SECONDS)).get()
                    .satisfies(state -> assertThat(state.getProviderMessageId()).isEqualTo("X"));
        }
    }

    @Test
    @Timeout(10)
    void closeWaitsForPublicationBoundary() throws Exception {
        MessageState accepted = httpState();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var storage = FileDlrStorage.open(root);
            storage.beforeCommit = c -> {
                entered.countDown();
                await(release);
            };
            var writer = executor.submit(() -> storage.recordProviderAccepted(accepted, "P", "X"));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            var closer = executor.submit(storage::close);
            Thread.sleep(200);
            assertThat(closer.isDone()).isFalse();
            release.countDown();
            writer.get(5, TimeUnit.SECONDS);
            closer.get(5, TimeUnit.SECONDS);
        }
        try (var reopened = FileDlrStorage.open(root)) {
            assertThat(reopened.correlationOwner("P", "X")).contains(accepted.getGatewayMsgId());
        }
    }

    private static void assertUnavailable(FileDlrStorage storage) {
        assertThatThrownBy(() -> storage.getState(httpState().getGatewayMsgId()))
                .hasMessage("fileDlrStorage|error|reason:unavailable");
        assertThatThrownBy(() -> storage.recordProviderAccepted(httpState(), "P", "later"))
                .hasMessage("fileDlrStorage|error|reason:unavailable");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("not released");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
