package gr.cytech.sendium.core.worker;

import gr.cytech.sendium.core.message.DlrReturnMetadata;
import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.outbound.OutboundWork.SourceId;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;

final class MemoryDlrTestSupport {
    static final Duration CORRELATION_TTL = Duration.ofMillis(100);
    static final Duration DELIVERY_TTL = Duration.ofMillis(200);
    static final Duration LEASE = Duration.ofMillis(25);

    private MemoryDlrTestSupport() {
    }

    static MessageState state(MessageState.DeliveryChannel channel) {
        var state = new MessageState(UUID.randomUUID().toString(), "account", "system",
                "sender", "recipient", channel == MessageState.DeliveryChannel.HTTP ? "https://callback.test/dlr" : null);
        state.setDeliveryChannel(channel);
        state.setHttpCallbackFormat(DlrReturnMetadata.HttpCallbackFormat.JSON_POST);
        return state;
    }

    static SourceId reserve(MemoryDlrReservations owner) {
        var source = new SourceId(UUID.randomUUID());
        var message = new StandardMessage();
        message.dlrReturnMetadata = DlrReturnMetadata.smpp("account", "system", "sender", "recipient");
        owner.reserveAdmission(source, message);
        return source;
    }

    static SourceId associate(MemoryDlrReservations owner, MessageState state) {
        var source = reserve(owner);
        owner.associateExecution(state.getGatewayMsgId(), Set.of(source));
        return source;
    }

    static void pending(MemoryDlrStorage storage, MessageState state) {
        String id = UUID.randomUUID().toString();
        storage.recordProviderAccepted(state, "P", id);
        storage.resolveDlr("P", id, MessageState.MessageStatus.DELIVERED, StandardMessage.DLR_STAT_DELIVRD, "0")
                .orElseThrow();
    }

    static MemoryDlrStorage storage(MemoryDlrReservations owner, Clock clock) {
        return new MemoryDlrStorage(owner, CORRELATION_TTL, DELIVERY_TTL, LEASE, clock);
    }

    static final class MutableClock extends Clock {
        private volatile long now;

        void set(long value) {
            now = value;
        }

        @Override
        public long millis() {
            return now;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(now);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(instant(), zone);
        }
    }
}
