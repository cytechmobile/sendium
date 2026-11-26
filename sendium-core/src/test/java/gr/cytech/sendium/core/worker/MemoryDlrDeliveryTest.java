package gr.cytech.sendium.core.worker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static gr.cytech.sendium.core.worker.MemoryDlrTestSupport.associate;
import static gr.cytech.sendium.core.worker.MemoryDlrTestSupport.pending;
import static gr.cytech.sendium.core.worker.MemoryDlrTestSupport.state;
import static gr.cytech.sendium.core.worker.MemoryDlrTestSupport.storage;
import static org.assertj.core.api.Assertions.assertThat;

@Timeout(20)
class MemoryDlrDeliveryTest {
    private final MemoryDlrTestSupport.MutableClock clock = new MemoryDlrTestSupport.MutableClock();
    private final MemoryDlrReservations owner = new MemoryDlrReservations(3);

    @ParameterizedTest
    @EnumSource(value = MessageState.DeliveryChannel.class, names = {"HTTP", "SMPP"})
    void pendingDeliveryRetainsCapacity(MessageState.DeliveryChannel channel) {
        var state = state(channel);
        var source = associate(owner, state);
        try (var storage = storage(owner, clock)) {
            pending(storage, state);
            owner.releaseOutbound(Set.of(source));
            assertThat(owner.usedCapacity()).isEqualTo(1);
            if (channel == MessageState.DeliveryChannel.SMPP) {
                assertThat(storage.listPendingSmppDeliveries("system")).hasSize(1);
                assertThat(storage.listPendingSmppDeliveries("another")).isEmpty();
            }
            storage.startDeliveryAttempt(state.getGatewayMsgId(), channel).orElseThrow();
            assertThat(storage.retryDelivery(state.getGatewayMsgId(), 1, " timeout ", 10)).isTrue();
            assertThat(owner.usedCapacity()).isEqualTo(1);
            assertThat(storage.getState(state.getGatewayMsgId()).orElseThrow().getLastDeliveryResult()).isEqualTo("timeout");
            clock.set(10);
            storage.startDeliveryAttempt(state.getGatewayMsgId(), channel).orElseThrow();
            storage.completeDelivery(state.getGatewayMsgId(), 2);
            assertThat(owner.usedCapacity()).isZero();
        }
    }

    @ParameterizedTest
    @EnumSource(value = MessageState.DeliveryChannel.class, names = {"HTTP", "SMPP"})
    void staleAttemptCannotReleaseNewClaim(MessageState.DeliveryChannel channel) {
        var state = state(channel);
        associate(owner, state);
        try (var storage = storage(owner, clock)) {
            pending(storage, state);
            var id = state.getGatewayMsgId();
            storage.startDeliveryAttempt(id, channel).orElseThrow();
            assertThat(storage.startDeliveryAttempt(id, channel)).isEmpty();
            clock.set(25);
            assertThat(storage.startDeliveryAttempt(id, channel)).get()
                    .extracting(MessageState::getDeliveryAttemptCount).isEqualTo(2);
            assertThat(storage.completeDelivery(id, 1)).isFalse();
            assertThat(storage.retryDelivery(id, 1, "old", 0)).isFalse();
            assertThat(storage.failDelivery(id, 1, "old")).isFalse();
            assertThat(storage.failInvalidDelivery(id, 1, "old")).isFalse();
            assertThat(storage.getState(id).orElseThrow().getDeliveryAttemptCount()).isEqualTo(2);
        }
    }

    @Test
    void httpClaimsRespectDueTimeLeaseAndBound() {
        try (var storage = storage(owner, clock)) {
            var first = state(MessageState.DeliveryChannel.HTTP);
            associate(owner, first);
            pending(storage, first);
            clock.set(10);
            var second = state(MessageState.DeliveryChannel.HTTP);
            associate(owner, second);
            pending(storage, second);
            assertThat(storage.claimDueHttpDeliveries(1)).extracting(MessageState::getGatewayMsgId)
                    .containsExactly(first.getGatewayMsgId());
            assertThat(storage.claimDueHttpDeliveries(1000)).extracting(MessageState::getGatewayMsgId)
                    .containsExactly(second.getGatewayMsgId());
            assertThat(storage.claimDueHttpDeliveries(1)).isEmpty();
            storage.retryDelivery(first.getGatewayMsgId(), 1, "retry", 100);
            clock.set(99);
            var claims = storage.claimDueHttpDeliveries(1000);
            assertThat(claims).extracting(MessageState::getGatewayMsgId).doesNotContain(first.getGatewayMsgId());
            clock.set(100);
            assertThat(storage.claimDueHttpDeliveries(1)).extracting(MessageState::getGatewayMsgId)
                    .containsExactly(first.getGatewayMsgId());
        }
    }

    @Test
    void failedDeliveryRemainsBoundedUntilOriginalExpiry() {
        var state = state(MessageState.DeliveryChannel.HTTP);
        var source = associate(owner, state);
        try (var storage = storage(owner, clock)) {
            pending(storage, state);
            owner.releaseOutbound(Set.of(source));
            storage.claimDueHttpDeliveries(1);
            assertThat(storage.failInvalidDelivery(state.getGatewayMsgId(), 1, "invalid")).isTrue();
            assertThat(storage.getState(state.getGatewayMsgId()).orElseThrow().getDeliveryStatus())
                    .isEqualTo(MessageState.DeliveryStatus.FAILED);
            assertThat(storage.claimDueHttpDeliveries(1)).isEmpty();
            assertThat(owner.usedCapacity()).isEqualTo(1);
            clock.set(200);
            storage.cleanup(10);
            assertThat(owner.usedCapacity()).isZero();
        }
    }

    @Test
    void concurrentClaimsCannotShareAnAttempt() throws Exception {
        var state = state(MessageState.DeliveryChannel.HTTP);
        associate(owner, state);
        try (var storage = storage(owner, clock); var threads = Executors.newFixedThreadPool(2)) {
            pending(storage, state);
            var start = new CountDownLatch(1);
            var first = threads.submit(() -> {
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                return storage.claimDueHttpDeliveries(1);
            });
            var second = threads.submit(() -> {
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                return storage.claimDueHttpDeliveries(1);
            });
            start.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS).size() + second.get(5, TimeUnit.SECONDS).size()).isEqualTo(1);
            assertThat(storage.getState(state.getGatewayMsgId()).orElseThrow().getDeliveryAttemptCount()).isEqualTo(1);
        }
    }

    @Test
    void smppReplayUsesResolutionOrderWithoutClaiming() {
        try (var storage = storage(owner, clock)) {
            var a = state(MessageState.DeliveryChannel.SMPP);
            var b = state(MessageState.DeliveryChannel.SMPP);
            associate(owner, a);
            associate(owner, b);
            storage.recordProviderAccepted(a, "P", "a");
            clock.set(10);
            storage.recordProviderAccepted(b, "P", "b");
            clock.set(20);
            storage.resolveDlr("P", "b", MessageState.MessageStatus.DELIVERED, 1, "0");
            clock.set(30);
            storage.resolveDlr("P", "a", MessageState.MessageStatus.DELIVERED, 1, "0");
            assertThat(storage.listPendingSmppDeliveries("system")).extracting(MessageState::getGatewayMsgId)
                    .containsExactly(b.getGatewayMsgId(), a.getGatewayMsgId());
            assertThat(storage.listPendingSmppDeliveries("system")).allSatisfy(receipt ->
                    assertThat(receipt.getDeliveryAttemptCount()).isZero());
        }
    }

    @Test
    void expiryFencesLateDeliveryCompletion() {
        var state = state(MessageState.DeliveryChannel.HTTP);
        var source = associate(owner, state);
        try (var storage = storage(owner, clock)) {
            pending(storage, state);
            owner.releaseOutbound(Set.of(source));
            clock.set(199);
            storage.claimDueHttpDeliveries(1);
            clock.set(200);
            assertThat(storage.completeDelivery(state.getGatewayMsgId(), 1)).isFalse();
            assertThat(storage.retryDelivery(state.getGatewayMsgId(), 1, "late", 300)).isFalse();
            storage.cleanup(10);
            assertThat(owner.usedCapacity()).isZero();
        }
    }
}
