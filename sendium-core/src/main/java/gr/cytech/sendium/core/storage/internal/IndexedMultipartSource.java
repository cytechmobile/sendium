package gr.cytech.sendium.core.storage.internal;

import gr.cytech.sendium.core.outbound.OutboundWork.SourceId;
import gr.cytech.sendium.core.storage.pending.MultipartMembership;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Internal disk-backed assembly composition. READY implies complete membership validation at open.
 * Metadata queries are bounded; publication through the owning pending store includes duplicate handles.
 */
public interface IndexedMultipartSource {
    List<UUID> dueMultipartGroups(Instant now, int limit);

    Optional<Group> multipartGroup(UUID group);

    void scheduleMultipartGroup(UUID group, Instant due);

    void finishMultipartGroup(UUID group);

    record Group(MultipartMembership identity, List<SourceId> originals, boolean scheduled) {
        public Group {
            java.util.Objects.requireNonNull(identity, "identity");
            originals = List.copyOf(originals);
            if (originals.isEmpty() || originals.size() > identity.key().total()) {
                throw new IllegalArgumentException("pendingMultipartRecovery|error|reason:invalidOriginalCount");
            }
        }
    }
}
