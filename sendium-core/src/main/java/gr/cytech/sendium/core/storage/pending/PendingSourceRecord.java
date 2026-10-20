package gr.cytech.sendium.core.storage.pending;

import gr.cytech.sendium.core.outbound.OutboundWork.SourceId;

import java.util.Objects;

/** Accepted source identity and disposition, independent of any physical storage backend. */
public record PendingSourceRecord(SourceId source, Disposition disposition, PendingMessageData message) {
    public PendingSourceRecord {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(disposition, "disposition");
        Objects.requireNonNull(message, "message");
        if (!source.value().toString().equalsIgnoreCase(message.gatewayId())) {
            throw new IllegalArgumentException("Pending source identity must match the gateway identity");
        }
    }

    public enum Disposition {
        READY, HELD
    }
}
