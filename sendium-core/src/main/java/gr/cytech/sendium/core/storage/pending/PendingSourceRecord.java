package gr.cytech.sendium.core.storage.pending;

import gr.cytech.sendium.core.outbound.OutboundWork.SourceId;
import gr.cytech.sendium.util.MessageUtil;

import java.util.Objects;

/** Accepted source identity and disposition, independent of any physical storage backend. */
public record PendingSourceRecord(SourceId source, Disposition disposition, PendingMessageData message, MultipartMembership multipart) {
    public PendingSourceRecord(SourceId source, Disposition disposition, PendingMessageData message) {
        this(source, disposition, message, null);
    }

    public PendingSourceRecord {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(disposition, "disposition");
        Objects.requireNonNull(message, "message");
        if (!source.value().toString().equalsIgnoreCase(message.gatewayId())) {
            throw new IllegalArgumentException("Pending source identity must match the gateway identity");
        }
        if (multipart != null) {
            var payload = message.restore();
            if (disposition != Disposition.HELD || !source.equals(multipart.source()) ||
                    !multipart.key().equals(MultipartKey.from(payload)) ||
                    multipart.ordinal() != MessageUtil.getNumberOfCurrentPart(payload)) {
                throw new IllegalArgumentException("Pending multipart membership must match the accepted source");
            }
        }
    }

    public enum Disposition {
        READY, HELD
    }
}
