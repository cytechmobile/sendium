package gr.cytech.sendium.core.worker;

import org.junit.jupiter.api.Test;

import java.util.List;

import static gr.cytech.sendium.core.worker.MemoryDlrTestSupport.associate;
import static gr.cytech.sendium.core.worker.MemoryDlrTestSupport.state;
import static gr.cytech.sendium.core.worker.MemoryDlrTestSupport.storage;
import static org.assertj.core.api.Assertions.assertThat;

class MemoryDlrBatchTest {
    @Test
    void failedEventDoesNotCorruptOtherResults() {
        var owner = new MemoryDlrReservations(3);
        var clock = new MemoryDlrTestSupport.MutableClock();
        var a = state(MessageState.DeliveryChannel.HTTP);
        var invalid = state(MessageState.DeliveryChannel.NONE);
        var c = state(MessageState.DeliveryChannel.SMPP);
        associate(owner, a);
        associate(owner, invalid);
        associate(owner, c);
        try (var storage = storage(owner, clock)) {
            var results = storage.applyProviderBatch(List.of(
                    new ProviderDlrEvent.Accepted(a, "P", "a"),
                    new ProviderDlrEvent.Accepted(invalid, "P", "b"),
                    new ProviderDlrEvent.Accepted(c, "P", "c"),
                    new ProviderDlrEvent.Accepted(a, "P", "a")));
            assertThat(results).hasSize(4);
            assertThat(results.get(0).failure()).isNull();
            assertThat(results.get(1).failure()).isInstanceOf(DlrStorageException.class);
            assertThat(results.get(2).failure()).isNull();
            assertThat(results.get(3).failure()).isNull();
            assertThat(storage.getState(a.getGatewayMsgId())).isPresent();
            assertThat(storage.getState(invalid.getGatewayMsgId())).isEmpty();
            assertThat(storage.getState(c.getGatewayMsgId())).isPresent();
            assertThat(owner.usedCapacity()).isEqualTo(3);
            assertThat(storage.applyProviderBatch(List.of(
                    new ProviderDlrEvent.Receipt("P", "b", MessageState.MessageStatus.DELIVERED, 1, "0")))
                    .getFirst().state()).isEmpty();
        }
    }

    @Test
    void rejectionWithoutProviderIdDoesNotInventCorrelation() {
        var owner = new MemoryDlrReservations(1);
        var clock = new MemoryDlrTestSupport.MutableClock();
        var state = state(MessageState.DeliveryChannel.SMPP);
        associate(owner, state);
        try (var storage = storage(owner, clock)) {
            var result = storage.recordProviderRejected(state, "P", null, 2, "22").orElseThrow();
            assertThat(result.getProviderName()).isNull();
            assertThat(result.getProviderMessageId()).isNull();
            assertThat(result.getLastDeliveryResult()).isEqualTo("provider_rejected");
            assertThat(storage.recordProviderRejected(state, "P", null, 2, "99")).isEmpty();
            assertThat(storage.getState(state.getGatewayMsgId()).orElseThrow().getErrorCode()).isEqualTo("22");
        }
    }
}
