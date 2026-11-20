package gr.cytech.sendium.core.worker;

import gr.cytech.sendium.core.worker.FileDlrTestSupport.MutableClock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.time.Duration;
import java.util.List;

import static gr.cytech.sendium.core.worker.FileDlrTestSupport.httpState;
import static gr.cytech.sendium.core.worker.FileDlrTestSupport.pending;
import static gr.cytech.sendium.core.worker.FileDlrTestSupport.raw;
import static gr.cytech.sendium.core.worker.FileDlrTestSupport.single;
import static gr.cytech.sendium.core.worker.FileDlrTestSupport.smppState;
import static org.assertj.core.api.Assertions.assertThat;

class FileDlrDeliveryTest {
    private static final Duration LEASE = Duration.ofMillis(25);

    @TempDir
    Path root;
    private final MutableClock clock = new MutableClock();

    @Test
    void dueHttpClaimsPersistBeforeReturn() {
        MessageState first;
        MessageState second;
        try (var storage = open()) {
            clock.set(10);
            first = pending(storage, httpState());
            clock.set(20);
            second = pending(storage, httpState());
            clock.set(30);
            pending(storage, httpState());

            clock.set(25);
            List<MessageState> claimed = storage.claimDueHttpDeliveries(1);
            assertThat(claimed).extracting(MessageState::getGatewayMsgId).containsExactly(first.getGatewayMsgId());
            assertThat(claimed.getFirst().getDeliveryAttemptCount()).isEqualTo(1);
            // The live lease hides the first receipt; the third is not due yet.
            assertThat(storage.claimDueHttpDeliveries(10)).extracting(MessageState::getGatewayMsgId)
                    .containsExactly(second.getGatewayMsgId());
        }
        try (var reopened = open()) {
            assertThat(reopened.claimDueHttpDeliveries(10)).isEmpty();
            assertThat(reopened.getState(first.getGatewayMsgId()).orElseThrow().getDeliveryAttemptCount()).isEqualTo(1);
            assertThat(reopened.getState(second.getGatewayMsgId()).orElseThrow().getLastAttemptAt()).isEqualTo(25);
        }
    }

    @Test
    void smppReplayUsesSystemIndexAndExistingOrder() {
        try (var storage = open()) {
            clock.set(1);
            MessageState late = smppState("A");
            storage.recordProviderAccepted(late, "P", "late");
            clock.set(2);
            MessageState early = smppState("A");
            storage.recordProviderAccepted(early, "P", "early");
            MessageState waiting = smppState("A");
            storage.recordProviderAccepted(waiting, "P", "waiting");
            pending(storage, smppState("B"));
            MessageState http = httpState();
            pending(storage, http);

            clock.set(20);
            storage.resolveDlr("P", "early", MessageState.MessageStatus.DELIVERED, 1, "0");
            clock.set(30);
            storage.resolveDlr("P", "late", MessageState.MessageStatus.DELIVERED, 1, "0");

            assertThat(storage.listPendingSmppDeliveries("A")).extracting(MessageState::getGatewayMsgId)
                    .containsExactly(early.getGatewayMsgId(), late.getGatewayMsgId());
            assertThat(storage.listPendingSmppDeliveries("missing")).isEmpty();
            assertThat(storage.listPendingSmppDeliveries(" ")).isEmpty();
        }
    }

    @Test
    void claimsSurviveReopenUntilLeaseExpiry() {
        MessageState http;
        MessageState smpp;
        try (var storage = FileDlrStorage.open(root, Duration.ofMillis(50), clock)) {
            http = pending(storage, httpState());
            smpp = pending(storage, smppState("A"));
            assertThat(storage.startDeliveryAttempt(http.getGatewayMsgId(), MessageState.DeliveryChannel.HTTP))
                    .get().extracting(MessageState::getDeliveryAttemptCount).isEqualTo(1);
            assertThat(storage.startDeliveryAttempt(smpp.getGatewayMsgId(), MessageState.DeliveryChannel.SMPP))
                    .isPresent();
            assertThat(storage.startDeliveryAttempt(smpp.getGatewayMsgId(), MessageState.DeliveryChannel.HTTP))
                    .isEmpty();
        }
        clock.set(49);
        try (var reopened = FileDlrStorage.open(root, Duration.ofMillis(50), clock)) {
            assertThat(reopened.claimDueHttpDeliveries(10)).isEmpty();
            for (MessageState state : List.of(http, smpp)) {
                assertThat(reopened.startDeliveryAttempt(state.getGatewayMsgId(), state.getDeliveryChannel())).isEmpty();
            }
            clock.set(50);
            for (MessageState state : List.of(http, smpp)) {
                assertThat(reopened.startDeliveryAttempt(state.getGatewayMsgId(), state.getDeliveryChannel()))
                        .get().extracting(MessageState::getDeliveryAttemptCount).isEqualTo(2);
            }
        }
    }

    @Test
    void staleOutcomesCannotMutateNewerAttempt() {
        MessageState state;
        try (var storage = open()) {
            state = pending(storage, smppState("A"));
            String id = state.getGatewayMsgId();
            storage.startDeliveryAttempt(id, MessageState.DeliveryChannel.SMPP).orElseThrow();
            clock.set(100);
            assertThat(storage.startDeliveryAttempt(id, MessageState.DeliveryChannel.SMPP))
                    .get().extracting(MessageState::getDeliveryAttemptCount).isEqualTo(2);

            assertThat(storage.completeDelivery(id, 1)).isFalse();
            assertThat(storage.retryDelivery(id, 1, "old", 0)).isFalse();
            assertThat(storage.failDelivery(id, 1, "old")).isFalse();
            assertThat(storage.failInvalidDelivery(id, 1, "old")).isFalse();
        }
        try (var reopened = open()) {
            MessageState current = reopened.getState(state.getGatewayMsgId()).orElseThrow();
            assertThat(current.getDeliveryAttemptCount()).isEqualTo(2);
            assertThat(current.getDeliveryStatus()).isEqualTo(MessageState.DeliveryStatus.PENDING);
            assertThat(current.getLastDeliveryResult()).isNull();
            assertThat(reopened.completeDelivery(state.getGatewayMsgId(), 1)).isFalse();
            assertThat(reopened.completeDelivery(state.getGatewayMsgId(), 2)).isTrue();
        }
    }

    @Test
    void matchingResultsPersistScheduleAndDisposition() throws Exception {
        MessageState state;
        try (var storage = open()) {
            state = pending(storage, httpState());
            assertThat(storage.claimDueHttpDeliveries(1)).hasSize(1);
            assertThat(storage.retryDelivery(state.getGatewayMsgId(), 1, "  timeout  ", 100)).isTrue();
        }
        clock.set(99);
        try (var reopened = open()) {
            MessageState retrying = reopened.getState(state.getGatewayMsgId()).orElseThrow();
            assertThat(retrying.getLastDeliveryResult()).isEqualTo("timeout");
            assertThat(retrying.getNextAttemptAt()).isEqualTo(100);
            assertThat(reopened.claimDueHttpDeliveries(10)).isEmpty();
            clock.set(100);
            assertThat(reopened.claimDueHttpDeliveries(10)).singleElement()
                    .extracting(MessageState::getDeliveryAttemptCount).isEqualTo(2);
            clock.set(150);
            assertThat(reopened.failDelivery(state.getGatewayMsgId(), 2, "gone")).isTrue();
        }
        try (var reopened = open()) {
            MessageState failed = reopened.getState(state.getGatewayMsgId()).orElseThrow();
            assertThat(failed.getDeliveryStatus()).isEqualTo(MessageState.DeliveryStatus.FAILED);
            assertThat(failed.getLastDeliveryResult()).isEqualTo("gone");
            assertThat(failed.getNextAttemptAt()).isNull();
            assertThat(reopened.claimDueHttpDeliveries(10)).isEmpty();
            assertThat(reopened.completeDelivery(state.getGatewayMsgId(), 2)).isFalse();
        }
        try (Connection connection = raw(root)) {
            assertThat(single(connection, "SELECT failed_at FROM dlr_message")).isEqualTo("150");
        }
    }

    @Test
    void matchingCompletionRemovesStateAndIndexes() throws Exception {
        MessageState state;
        try (var storage = open()) {
            state = smppState("A");
            storage.recordProviderAccepted(state, "P", "X1");
            storage.recordProviderAccepted(state, "P", "X2");
            storage.resolveDlr("P", "X1", MessageState.MessageStatus.DELIVERED, 1, "0").orElseThrow();
            storage.startDeliveryAttempt(state.getGatewayMsgId(), MessageState.DeliveryChannel.SMPP).orElseThrow();

            assertThat(storage.completeDelivery(state.getGatewayMsgId(), 1)).isTrue();
            assertThat(storage.getState(state.getGatewayMsgId())).isEmpty();
            assertThat(storage.listPendingSmppDeliveries("A")).isEmpty();
            assertThat(storage.completeDelivery(state.getGatewayMsgId(), 1)).isFalse();
        }
        try (Connection connection = raw(root)) {
            assertThat(single(connection, "SELECT count(*) FROM dlr_message")).isEqualTo("0");
            assertThat(single(connection, "SELECT count(*) FROM provider_correlation")).isEqualTo("0");
        }
    }

    private FileDlrStorage open() {
        return FileDlrStorage.open(root, LEASE, clock);
    }
}
