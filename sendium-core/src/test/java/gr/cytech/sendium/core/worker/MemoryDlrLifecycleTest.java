package gr.cytech.sendium.core.worker;

import gr.cytech.sendium.core.message.StandardMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static gr.cytech.sendium.core.worker.MemoryDlrTestSupport.associate;
import static gr.cytech.sendium.core.worker.MemoryDlrTestSupport.pending;
import static gr.cytech.sendium.core.worker.MemoryDlrTestSupport.reserve;
import static gr.cytech.sendium.core.worker.MemoryDlrTestSupport.state;
import static gr.cytech.sendium.core.worker.MemoryDlrTestSupport.storage;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

@Timeout(20)
class MemoryDlrLifecycleTest {
    private final MemoryDlrTestSupport.MutableClock clock = new MemoryDlrTestSupport.MutableClock();
    private final MemoryDlrReservations owner = new MemoryDlrReservations(3);

    @Test
    void providerAliasesReuseAllContributingSources() {
        var state = state(MessageState.DeliveryChannel.SMPP);
        var a = reserve(owner);
        var b = reserve(owner);
        var duplicate = reserve(owner);
        // Receipt return IDs intentionally omit the separately accepted duplicate.
        state.setReassembledParts(List.of(a.value().toString(), b.value().toString()));
        owner.associateExecution(state.getGatewayMsgId(), Set.of(a, b, duplicate));
        try (var storage = storage(owner, clock)) {
            for (int i = 0; i < 4; i++) {
                storage.recordProviderAccepted(state, "P", "part-" + i);
            }
            assertThat(owner.usedCapacity()).isEqualTo(3);
            owner.releaseOutbound(Set.of(a, b, duplicate));
            assertThat(owner.usedCapacity()).isEqualTo(3);
            storage.resolveDlr("P", "part-0", MessageState.MessageStatus.DELIVERED, 1, "0").orElseThrow();
            storage.startDeliveryAttempt(state.getGatewayMsgId(), MessageState.DeliveryChannel.SMPP).orElseThrow();
            assertThat(storage.completeDelivery(state.getGatewayMsgId(), 1)).isTrue();
            assertThat(owner.usedCapacity()).isZero();
        }
    }

    @Test
    void firstTerminalResultWins() {
        var state = state(MessageState.DeliveryChannel.HTTP);
        associate(owner, state);
        try (var storage = storage(owner, clock)) {
            storage.recordProviderAccepted(state, "P", "first");
            storage.recordProviderAccepted(state, "P", "second");
            assertThat(storage.resolveDlr("P", "first", MessageState.MessageStatus.SENT,
                    StandardMessage.DLR_STAT_ACCEPTD, "0")).isEmpty();
            assertThat(storage.resolveDlr("P", "first", MessageState.MessageStatus.DELIVERED, 1, "0")).isPresent();
            assertThat(storage.resolveDlr("P", "second", MessageState.MessageStatus.FAILED, 2, "1")).isEmpty();
            assertThat(storage.getState(state.getGatewayMsgId()).orElseThrow().getStatus())
                    .isEqualTo(MessageState.MessageStatus.DELIVERED);
            assertThatThrownBy(() -> storage.recordProviderAccepted(state, "P", "late"))
                    .isInstanceOf(DlrStorageException.class);
        }
    }

    @Test
    void earlyReceiptCannotReleaseOutboundHold() {
        var state = state(MessageState.DeliveryChannel.HTTP);
        var source = associate(owner, state);
        try (var storage = storage(owner, clock)) {
            pending(storage, state);
            storage.claimDueHttpDeliveries(1);
            storage.completeDelivery(state.getGatewayMsgId(), 1);
            assertThat(owner.usedCapacity()).isEqualTo(1);
            assertThatThrownBy(() -> storage.recordProviderAccepted(state, "P", "late"))
                    .isInstanceOf(DlrStorageException.class);
            owner.releaseOutbound(Set.of(source));
            owner.releaseOutbound(Set.of(source));
            assertThat(owner.usedCapacity()).isZero();
        }
    }

    @Test
    void recreationStartsEmpty() {
        var state = state(MessageState.DeliveryChannel.SMPP);
        associate(owner, state);
        var storage = storage(owner, clock);
        pending(storage, state);
        storage.close();
        storage.close();
        assertThat(owner.usedCapacity()).isZero();
        assertThatThrownBy(storage::verifyAvailable).isInstanceOf(DlrStorageException.class);
        try (var recreated = storage(new MemoryDlrReservations(3), clock)) {
            assertThat(recreated.getState(state.getGatewayMsgId())).isEmpty();
            assertThat(recreated.listPendingSmppDeliveries("system")).isEmpty();
            assertThat(recreated.resolveDlr("P", "old", MessageState.MessageStatus.DELIVERED, 1, "0")).isEmpty();
        }
    }

    @Test
    void rejectsUnreservedOrConflictingExecutionOwnership() {
        var state = state(MessageState.DeliveryChannel.HTTP);
        try (var storage = storage(owner, clock)) {
            assertThatThrownBy(() -> storage.recordProviderAccepted(state, "P", "missing"))
                    .isInstanceOf(DlrStorageException.class);
            var source = associate(owner, state);
            var other = reserve(owner);
            assertThatThrownBy(() -> owner.associateExecution(state.getGatewayMsgId(), Set.of(other)))
                    .isInstanceOf(DlrStorageException.class);
            owner.associateExecution(state.getGatewayMsgId(), Set.of(source));
            assertThat(owner.usedCapacity()).isEqualTo(2);
        }
    }

    @Test
    void snapshotsDoNotExposeMutableInternalState() {
        var state = state(MessageState.DeliveryChannel.SMPP);
        state.setReassembledParts(List.of("first"));
        associate(owner, state);
        try (var storage = storage(owner, clock)) {
            storage.recordProviderAccepted(state, "P", "id");
            state.setReassembledParts(List.of("mutated"));
            var copy = storage.getState(state.getGatewayMsgId()).orElseThrow();
            copy.setDeliveryAttemptCount(99);
            copy.setStatus(MessageState.MessageStatus.FAILED);
            assertThat(storage.getState(state.getGatewayMsgId())).get().satisfies(actual -> {
                assertThat(actual.getDeliveryAttemptCount()).isZero();
                assertThat(actual.getReassembledParts()).containsExactly("first");
                assertThat(actual.getStatus()).isEqualTo(MessageState.MessageStatus.SENT);
            });
        }
    }

    @Test
    void concurrentTerminalReceiptsChooseExactlyOneResult() throws Exception {
        var state = state(MessageState.DeliveryChannel.HTTP);
        associate(owner, state);
        try (var storage = storage(owner, clock); var threads = Executors.newFixedThreadPool(2)) {
            storage.recordProviderAccepted(state, "P", "one");
            storage.recordProviderAccepted(state, "P", "two");
            var start = new CountDownLatch(1);
            var first = threads.submit(() -> {
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                return storage.resolveDlr("P", "one", MessageState.MessageStatus.DELIVERED, 1, "0");
            });
            var second = threads.submit(() -> {
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                return storage.resolveDlr("P", "two", MessageState.MessageStatus.FAILED, 2, "1");
            });
            start.countDown();
            var a = first.get(5, TimeUnit.SECONDS);
            var b = second.get(5, TimeUnit.SECONDS);
            assertThat((a.isPresent() ? 1 : 0) + (b.isPresent() ? 1 : 0)).isEqualTo(1);
            var winner = a.orElseGet(b::orElseThrow);
            assertThat(storage.getState(state.getGatewayMsgId()).orElseThrow().getStatus()).isEqualTo(winner.getStatus());
        }
    }

    @Test
    void snapshotCountsTrackLifecycleWithoutScanningBeyondBounds() {
        var state = state(MessageState.DeliveryChannel.HTTP);
        var source = associate(owner, state);
        try (var storage = storage(owner, clock)) {
            storage.recordProviderAccepted(state, "P", "id");
            assertThat(storage.readMetricSnapshot(1, Duration.ofSeconds(1)))
                    .contains(new DlrStorageBackend.ReceiptCounts(1, 0, 0));
            storage.resolveDlr("P", "id", MessageState.MessageStatus.DELIVERED, 1, "0").orElseThrow();
            assertThat(storage.readMetricSnapshot(1, Duration.ofSeconds(1)))
                    .contains(new DlrStorageBackend.ReceiptCounts(0, 1, 0));
            storage.claimDueHttpDeliveries(1);
            storage.failDelivery(state.getGatewayMsgId(), 1, "failure");
            assertThat(storage.readMetricSnapshot(1, Duration.ofSeconds(1)))
                    .contains(new DlrStorageBackend.ReceiptCounts(0, 0, 1));
            owner.releaseOutbound(Set.of(source));
            clock.set(200);
            storage.cleanup(10);
            assertThat(storage.readMetricSnapshot(1, Duration.ofSeconds(1)))
                    .contains(new DlrStorageBackend.ReceiptCounts(0, 0, 0));
        }
    }

    @Test
    void reusedProviderIdTransfersOnlyThatCorrelation() {
        var a = state(MessageState.DeliveryChannel.HTTP);
        var b = state(MessageState.DeliveryChannel.HTTP);
        var sourceA = associate(owner, a);
        associate(owner, b);
        try (var storage = storage(owner, clock)) {
            storage.recordProviderAccepted(a, "P", "reused");
            clock.set(10);
            storage.recordProviderAccepted(b, "P", "reused");
            owner.releaseOutbound(Set.of(sourceA));
            assertThat(storage.getState(a.getGatewayMsgId())).get().satisfies(previous -> {
                assertThat(previous.getProviderName()).isNull();
                assertThat(previous.getProviderMessageId()).isNull();
            });
            clock.set(100);
            storage.cleanup(10);
            assertThat(storage.getState(a.getGatewayMsgId())).isEmpty();
            assertThat(owner.usedCapacity()).isEqualTo(1);
            assertThat(storage.resolveDlr("P", "reused", MessageState.MessageStatus.DELIVERED, 1, "0"))
                    .get().extracting(MessageState::getGatewayMsgId).isEqualTo(b.getGatewayMsgId());
        }
    }

    @Test
    void internalFailureLatchesAvailabilityWithoutFallback() {
        var state = state(MessageState.DeliveryChannel.HTTP);
        associate(owner, state);
        var failingClock = spy(clock);
        var failure = new IllegalStateException("testClock|error|reason:injectedFailure");
        try (var storage = storage(owner, failingClock)) {
            doThrow(failure).when(failingClock).millis();
            assertThatThrownBy(() -> storage.recordProviderAccepted(state, "P", "id")).isSameAs(failure);
            assertThatThrownBy(storage::verifyAvailable).isInstanceOf(DlrStorageException.class).hasCause(failure);
            assertThatThrownBy(() -> storage.claimDueHttpDeliveries(1)).isInstanceOf(DlrStorageException.class);
            assertThat(owner.usedCapacity()).isEqualTo(1);
        }
    }
}
