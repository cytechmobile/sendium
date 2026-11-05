package gr.cytech.sendium.core.storage.pending;

import gr.cytech.sendium.core.outbound.OutboundWork.SourceId;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable admission facts: original source UUID, assigned group instance, ordinal and saved deadline. */
public record MultipartMembership(SourceId source, UUID groupId, MultipartKey key, int ordinal, Instant deadline, long groupOrder,
                                  SourceId originalSource) {
    public MultipartMembership(SourceId source, UUID groupId, MultipartKey key, int ordinal, Instant deadline, long groupOrder) {
        this(source, groupId, key, ordinal, deadline, groupOrder, source);
    }

    public MultipartMembership {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(originalSource, "originalSource");
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(deadline, "deadline");
        if (ordinal < 1 || ordinal > key.total() || groupOrder < 0) {
            throw new IllegalArgumentException("Invalid multipart membership");
        }
    }

    public boolean duplicate() {
        return !source.equals(originalSource);
    }
}
