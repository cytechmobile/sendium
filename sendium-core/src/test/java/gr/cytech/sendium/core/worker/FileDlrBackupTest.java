package gr.cytech.sendium.core.worker;

import gr.cytech.sendium.core.worker.FileDlrTestSupport.MutableClock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Duration;
import java.util.stream.Stream;

import static gr.cytech.sendium.core.worker.FileDlrTestSupport.httpState;
import static gr.cytech.sendium.core.worker.FileDlrTestSupport.pending;
import static gr.cytech.sendium.core.worker.FileDlrTestSupport.raw;
import static gr.cytech.sendium.core.worker.FileDlrTestSupport.single;
import static gr.cytech.sendium.core.worker.FileDlrTestSupport.smppState;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileDlrBackupTest {
    private static final Duration LEASE = Duration.ofMinutes(5);
    private static final String SNAPSHOT = """
            SELECT group_concat(row, ';') FROM (
                SELECT gateway_message_id || ',' || delivery_status || ',' || delivery_attempt_count || ',' ||
                    ifnull(claimed_until, '-') || ',' || ifnull(next_attempt_at, '-') || ',' ||
                    ifnull(failed_at, '-') || ',' || ifnull(last_delivery_result, '-') AS row
                FROM dlr_message ORDER BY gateway_message_id)
            """;

    @TempDir Path temp;

    @Test
    void stoppedBackupRestoresVersionedLifecycle() throws Exception {
        Path live = temp.resolve("live");
        Path backup = temp.resolve("backup");
        Path restored = temp.resolve("restored");
        var clock = new MutableClock();
        clock.set(1_000);
        MessageState waiting = httpState();
        MessageState http;
        MessageState smpp;
        MessageState failed;
        try (var storage = FileDlrStorage.open(live, LEASE, clock)) {
            storage.recordProviderAccepted(waiting, "P", "waiting");
            http = pending(storage, httpState());
            smpp = pending(storage, smppState("A"));
            failed = pending(storage, smppState("A"));
            storage.claimDueHttpDeliveries(1).getFirst();
            storage.retryDelivery(http.getGatewayMsgId(), 1, "timeout", 5_000);
            storage.startDeliveryAttempt(smpp.getGatewayMsgId(), MessageState.DeliveryChannel.SMPP).orElseThrow();
            storage.startDeliveryAttempt(failed.getGatewayMsgId(), MessageState.DeliveryChannel.SMPP).orElseThrow();
            clock.set(2_000);
            storage.failDelivery(failed.getGatewayMsgId(), 1, "gone");
        }
        // The owner is stopped: copy the complete namespace, then let the original diverge.
        copyTree(live, backup);
        String expected = snapshot(backup);
        try (var storage = FileDlrStorage.open(live, LEASE, clock)) {
            storage.resolveDlr("P", "waiting", MessageState.MessageStatus.DELIVERED, 1, "0").orElseThrow();
        }

        copyTree(backup, restored);
        clock.set(2_500);
        try (var storage = FileDlrStorage.open(restored, LEASE, clock)) {
            assertThat(storage.correlationOwner("P", "waiting")).contains(waiting.getGatewayMsgId());
            assertThat(storage.getState(http.getGatewayMsgId()).orElseThrow())
                    .returns(1, MessageState::getDeliveryAttemptCount)
                    .returns("timeout", MessageState::getLastDeliveryResult)
                    .returns(5_000L, MessageState::getNextAttemptAt);
            // The restored SMPP lease still fences a second start until it expires.
            assertThat(storage.startDeliveryAttempt(smpp.getGatewayMsgId(), MessageState.DeliveryChannel.SMPP))
                    .isEmpty();
            assertThat(storage.getState(failed.getGatewayMsgId()).orElseThrow().getDeliveryStatus())
                    .isEqualTo(MessageState.DeliveryStatus.FAILED);
        }
        assertThat(snapshot(restored)).isEqualTo(expected);
        try (Connection connection = raw(restored)) {
            assertThat(single(connection, "PRAGMA user_version")).isEqualTo(Integer.toString(FileDlrStorage.SCHEMA_VERSION));
            assertThat(single(connection, "PRAGMA application_id"))
                    .isEqualTo(Integer.toString(FileDlrStorage.APPLICATION_ID));
            assertThat(single(connection, "SELECT failed_at FROM dlr_message WHERE delivery_status = 'FAILED'"))
                    .isEqualTo("2000");
        }

        // A live owner cannot be backed up into, or restored over, by a second owner.
        try (var owner = FileDlrStorage.open(restored, LEASE, clock)) {
            assertThatThrownBy(() -> FileDlrStorage.open(restored, LEASE, clock))
                    .isInstanceOf(DlrStorageException.class).hasMessageContaining("ownershipConflict");
        }
    }

    private static String snapshot(Path directory) throws Exception {
        try (Connection connection = raw(directory)) {
            return single(connection, SNAPSHOT);
        }
    }

    private static void copyTree(Path source, Path target) throws IOException {
        assertThat(target).doesNotExist();
        try (Stream<Path> files = Files.walk(source)) {
            for (Path path : files.toList()) {
                Path destination = target.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.copy(path, destination);
                }
            }
        }
    }
}
