package gr.cytech.sendium.core.worker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static gr.cytech.sendium.core.worker.MemoryDlrTestSupport.associate;
import static gr.cytech.sendium.core.worker.MemoryDlrTestSupport.pending;
import static gr.cytech.sendium.core.worker.MemoryDlrTestSupport.state;
import static gr.cytech.sendium.core.worker.MemoryDlrTestSupport.storage;
import static org.assertj.core.api.Assertions.assertThat;

@Timeout(20)
class MemoryDlrRetentionTest {
    private final MemoryDlrTestSupport.MutableClock clock = new MemoryDlrTestSupport.MutableClock();
    private final MemoryDlrReservations owner = new MemoryDlrReservations(5);

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void expiryPreservesOutboundHold(boolean delivery) {
        var state = state(MessageState.DeliveryChannel.HTTP);
        var source = associate(owner, state);
        try (var storage = storage(owner, clock)) {
            if (delivery) {
                pending(storage, state);
            } else {
                storage.recordProviderAccepted(state, "P", "id");
            }
            clock.set(delivery ? 200 : 100);
            storage.cleanup(10);
            assertThat(storage.getState(state.getGatewayMsgId())).isEmpty();
            assertThat(owner.usedCapacity()).isEqualTo(1);
            owner.releaseOutbound(Set.of(source));
            assertThat(owner.usedCapacity()).isZero();
        }
    }

    @Test
    void cleanupHonorsBudgetWithoutReentrantDeadlock() throws Exception {
        try (var storage = storage(owner, clock)) {
            for (int i = 0; i < 5; i++) {
                var state = state(MessageState.DeliveryChannel.HTTP);
                var source = associate(owner, state);
                pending(storage, state);
                owner.releaseOutbound(Set.of(source));
            }
            clock.set(200);
            assertThat(storage.cleanup(2)).isEqualTo(2);
            assertThat(owner.usedCapacity()).isEqualTo(3);
            // No completion or storage callback is invoked by this synchronous backend.
            // Another thread can acquire the shared owner immediately after the bounded call.
            try (var threads = Executors.newSingleThreadExecutor()) {
                assertThat(threads.submit(owner::usedCapacity).get(5, TimeUnit.SECONDS)).isEqualTo(3);
            }
            assertThat(storage.cleanup(2)).isEqualTo(2);
            assertThat(storage.cleanup(2)).isEqualTo(1);
            assertThat(storage.cleanup(2)).isZero();
            assertThat(owner.usedCapacity()).isZero();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void retriesDoNotRenewRetentionDeadline(boolean delivery) {
        var state = state(MessageState.DeliveryChannel.HTTP);
        associate(owner, state);
        try (var storage = storage(owner, clock)) {
            if (delivery) {
                pending(storage, state);
                clock.set(199);
                storage.claimDueHttpDeliveries(1);
                storage.retryDelivery(state.getGatewayMsgId(), 1, "retry", 300);
            } else {
                storage.recordProviderAccepted(state, "P", "id");
                clock.set(99);
                storage.recordProviderAccepted(state, "P", "id");
            }
            assertThat(storage.getState(state.getGatewayMsgId())).isPresent();
            clock.set(delivery ? 200 : 100);
            storage.cleanup(10);
            assertThat(storage.getState(state.getGatewayMsgId())).isEmpty();
            if (!delivery) {
                storage.recordProviderAccepted(state, "P", "id");
                assertThat(storage.getState(state.getGatewayMsgId())).isEmpty();
            }
        }
    }

    @Test
    void eachNewProviderAliasHasItsOwnOriginalDeadline() {
        var state = state(MessageState.DeliveryChannel.HTTP);
        associate(owner, state);
        try (var storage = storage(owner, clock)) {
            storage.recordProviderAccepted(state, "P", "early");
            clock.set(90);
            storage.recordProviderAccepted(state, "P", "later");
            clock.set(100);
            storage.cleanup(1);
            storage.recordProviderAccepted(state, "P", "early");
            assertThat(storage.resolveDlr("P", "early", MessageState.MessageStatus.DELIVERED, 1, "0")).isEmpty();
            assertThat(storage.resolveDlr("P", "later", MessageState.MessageStatus.DELIVERED, 1, "0")).isPresent();
        }
    }

    @Test
    void expiredDeliveryCannotBeClaimedBeforeScheduledCleanup() {
        var state = state(MessageState.DeliveryChannel.SMPP);
        associate(owner, state);
        try (var storage = storage(owner, clock)) {
            pending(storage, state);
            clock.set(200);
            assertThat(storage.listPendingSmppDeliveries("system")).isEmpty();
            assertThat(storage.startDeliveryAttempt(state.getGatewayMsgId(), MessageState.DeliveryChannel.SMPP)).isEmpty();
            assertThat(storage.readMetricSnapshot(5, Duration.ofSeconds(1))).isPresent();
        }
    }
}
