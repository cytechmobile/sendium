package gr.cytech.sendium.core.worker;

import gr.cytech.sendium.core.worker.FileDlrTestSupport.MutableClock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static gr.cytech.sendium.core.worker.FileDlrTestSupport.httpState;
import static gr.cytech.sendium.core.worker.FileDlrTestSupport.pending;
import static gr.cytech.sendium.core.worker.FileDlrTestSupport.raw;
import static gr.cytech.sendium.core.worker.FileDlrTestSupport.seed;
import static gr.cytech.sendium.core.worker.FileDlrTestSupport.single;
import static gr.cytech.sendium.core.worker.FileDlrTestSupport.smppState;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileDlrRetentionTest {
    private static final Duration CLAIM = Duration.ofMinutes(5);
    private static final long WEEK = Duration.ofDays(7).toMillis();
    private static final String FAILED_COUNT = "SELECT count(*) FROM dlr_message WHERE delivery_status = 'FAILED'";

    @TempDir
    Path root;
    private final MutableClock clock = new MutableClock();

    @Test
    void oldActiveWorkSurvivesCleanup() {
        List<String> active = new ArrayList<>();
        MessageState failed;
        try (var storage = open()) {
            MessageState waiting = httpState();
            storage.recordProviderAccepted(waiting, "P", "waiting");
            active.add(waiting.getGatewayMsgId());
            active.add(pending(storage, httpState()).getGatewayMsgId());
            MessageState claimed = pending(storage, smppState("A"));
            storage.startDeliveryAttempt(claimed.getGatewayMsgId(), MessageState.DeliveryChannel.SMPP).orElseThrow();
            active.add(claimed.getGatewayMsgId());
            MessageState retrying = pending(storage, httpState());
            storage.claimDueHttpDeliveries(10).forEach(state -> {
                if (state.getGatewayMsgId().equals(retrying.getGatewayMsgId())) {
                    storage.retryDelivery(state.getGatewayMsgId(), 1, "timeout", 60 * WEEK);
                }
            });
            active.add(retrying.getGatewayMsgId());
            failed = failedAt(storage, 0);

            // A month later the next write runs the periodic cleanup.
            clock.set(30 * WEEK);
            storage.recordProviderAccepted(httpState(), "P", "trigger");
            assertThat(storage.getState(failed.getGatewayMsgId())).isEmpty();
            assertThat(storage.expireFailures(1_000)).isZero();
            for (String id : active) {
                assertThat(storage.getState(id)).as(id).isPresent();
            }
            assertThat(storage.correlationOwner("P", "waiting")).contains(waiting.getGatewayMsgId());
        }
    }

    @Test
    void failedExpiryUsesFailureInstantAndConfiguredDuration() {
        try (var storage = open()) {
            MessageState failed = failedAt(storage, 1_000);
            clock.set(1_000 + WEEK - 1);
            assertThat(storage.expireFailures(10)).isZero();
            assertThat(storage.getState(failed.getGatewayMsgId())).isPresent();
            clock.set(1_000 + WEEK);
            assertThat(storage.expireFailures(10)).isOne();
            assertThat(storage.getState(failed.getGatewayMsgId())).isEmpty();
        }
        Duration hour = Duration.ofHours(1);
        clock.set(0);
        try (var storage = FileDlrStorage.open(root, CLAIM, hour, clock)) {
            MessageState failed = failedAt(storage, 10);
            clock.set(10 + hour.toMillis() - 1);
            assertThat(storage.expireFailures(10)).isZero();
            clock.set(10 + hour.toMillis());
            assertThat(storage.expireFailures(10)).isOne();
            assertThat(storage.getState(failed.getGatewayMsgId())).isEmpty();
        }
    }

    @Test
    void cleanupCommitsBoundedConsistentUnits() throws SQLException {
        FileDlrStorage.open(root, CLAIM, clock).close();
        seed(root, 2_001, "HTTP", "FAILED", "system", 0);
        List<String> committedFailures = new ArrayList<>();
        try (var storage = open()) {
            storage.beforeCommit = c -> committedFailures.add(single(c, FAILED_COUNT));
            clock.set(WEEK + Duration.ofHours(1).toMillis());
            MessageState trigger = httpState();
            storage.recordProviderAccepted(trigger, "P", "trigger");
            // Three cleanup units of at most 1,000 rows each, then the triggering write.
            assertThat(committedFailures).containsExactly("1001", "1", "0", "0");
            assertThat(storage.getState(trigger.getGatewayMsgId())).isPresent();
        }
    }

    @Test
    void cleanupPublicationFailureLatchesUnavailable() throws SQLException {
        FileDlrStorage.open(root, CLAIM, clock).close();
        seed(root, 2_001, "SMPP", "FAILED", "system", 0);
        seed(root, 5, "SMPP", "PENDING", "system", 0);
        try (var storage = open()) {
            int[] commits = {0};
            storage.beforeCommit = c -> {
                if (++commits[0] == 2) {
                    c.close(); // the second unit cannot publish
                }
            };
            clock.set(WEEK + Duration.ofHours(1).toMillis());
            assertThatThrownBy(() -> storage.recordProviderAccepted(httpState(), "P", "trigger"))
                    .isInstanceOf(DlrStorageException.class).hasMessageContaining("reason:unavailable");
            assertThatThrownBy(() -> storage.getState(httpState().getGatewayMsgId()))
                    .isInstanceOf(DlrStorageException.class);
        }
        try (Connection connection = raw(root)) {
            // The first unit stays committed; the failed unit and the triggering write are absent.
            assertThat(single(connection, FAILED_COUNT)).isEqualTo("1001");
            assertThat(single(connection, "SELECT count(*) FROM dlr_message WHERE delivery_status = 'PENDING'"))
                    .isEqualTo("5");
            assertThat(single(connection, "SELECT count(*) FROM dlr_message WHERE delivery_status = 'WAITING_PROVIDER'"))
                    .isEqualTo("0");
        }
        try (var reopened = open()) {
            assertThat(reopened.expireFailures(2_000)).isEqualTo(1_001);
        }
    }

    private MessageState failedAt(FileDlrStorage storage, long failedAt) {
        clock.set(failedAt);
        MessageState state = pending(storage, smppState("A"));
        storage.startDeliveryAttempt(state.getGatewayMsgId(), MessageState.DeliveryChannel.SMPP).orElseThrow();
        assertThat(storage.failDelivery(state.getGatewayMsgId(), 1, "gone")).isTrue();
        return state;
    }

    private FileDlrStorage open() {
        return FileDlrStorage.open(root, CLAIM, clock);
    }
}
