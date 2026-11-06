package gr.cytech.sendium.core.storage.file;

import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.outbound.OutboundWork.SourceId;
import gr.cytech.sendium.core.storage.OutboundStorageException;
import gr.cytech.sendium.core.storage.PendingMessageStore;
import gr.cytech.sendium.core.storage.pending.MultipartMembership;
import gr.cytech.sendium.core.storage.pending.PendingMessageData;
import gr.cytech.sendium.core.storage.pending.PendingSourceRecord;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static gr.cytech.sendium.core.storage.OutboundStage.Role.PENDING;
import static gr.cytech.sendium.core.storage.OutboundStorageException.Reason.CAPACITY_EXCEEDED;
import static gr.cytech.sendium.core.storage.OutboundStorageException.Reason.INVALID_TRANSITION;
import static gr.cytech.sendium.core.storage.OutboundStorageException.Reason.UNAVAILABLE;
import static gr.cytech.sendium.core.storage.OutboundStorageException.Reason.UNSUPPORTED;

/**
 * File-owned accepted sources using the explicit StandardMessage representation, not Java serialization.
 * Application profile enablement follows multipart and router integration; these are not yet supported here.
 */
public final class FilePendingMessageStore implements PendingMessageStore<StandardMessage> {
    private final PendingSpool spool;
    private final int capacity;
    private State state = State.NEW;
    private long retained;

    public FilePendingMessageStore(Path root, int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("pendingConfiguration|error|reason:nonPositiveCapacity");
        }
        this.spool = new PendingSpool(root);
        this.capacity = capacity;
    }

    @Override
    public synchronized void open() {
        if (state == State.READY) {
            requireReady();
            return;
        }
        if (state != State.NEW) {
            throw new OutboundStorageException(PENDING, INVALID_TRANSITION,
                    "pendingLifecycle|error|reason:cannotReopen");
        }
        try {
            spool.open();
            retained = spool.recoveredSources();
            // Do not claim successful recovery of source types not implemented by this increment.
            SourceId cursor = null;
            while (true) {
                List<SourceId> page = spool.sourcesAfter(cursor, 128);
                if (page.isEmpty()) {
                    break;
                }
                for (SourceId source : page) {
                    if (spool.find(source).orElseThrow().multipart() != null) {
                        throw unsupported("pendingRecovery|error|reason:multipartRecoveryUnavailable");
                    }
                }
                cursor = page.getLast();
            }
            state = State.READY;
        } catch (RuntimeException failure) {
            state = State.FAILED;
            try {
                spool.close();
            } catch (RuntimeException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    @Override
    public synchronized Status status() {
        State effective = state == State.READY && !spool.isReady() ? State.FAILED : state;
        return new Status("file", true, effective);
    }

    @Override
    public synchronized SourceId admit(SourceId source, StandardMessage message) {
        return admitSource(source, message, PendingSourceRecord.Disposition.READY);
    }

    @Override
    public synchronized SourceId admitHeld(SourceId source, StandardMessage message) {
        return admitSource(source, message, PendingSourceRecord.Disposition.HELD);
    }

    private SourceId admitSource(SourceId source, StandardMessage message, PendingSourceRecord.Disposition disposition) {
        requireReady();
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(message, "message");
        if (spool.find(source).isPresent()) {
            return source;
        }
        if (retained >= capacity) {
            throw new OutboundStorageException(PENDING, CAPACITY_EXCEEDED,
                    "pendingAdmission|error|sourceId:" + source.value() + "|reason:capacityExceeded");
        }
        PendingSourceRecord record;
        try {
            record = new PendingSourceRecord(source, disposition, PendingMessageData.capture(message));
        } catch (IllegalArgumentException failure) {
            throw unsupported("pendingAdmission|error|sourceId:" + source.value() + "|reason:invalidSourceIdentity");
        }
        spool.publish(record);
        retained++;
        return source;
    }

    @Override
    public synchronized Optional<StandardMessage> find(SourceId source) {
        requireReady();
        return spool.find(source).map(record -> record.message().restore());
    }

    @Override
    public synchronized List<SourceId> sourcesAfter(SourceId afterExclusive, int limit) {
        requireReady();
        return spool.sourcesAfter(afterExclusive, limit);
    }

    @Override
    public synchronized void complete(Set<SourceId> sources) {
        requireReady();
        for (SourceId source : Set.copyOf(sources)) {
            if (spool.remove(source)) {
                retained--;
            }
        }
    }

    @Override
    public synchronized MultipartMembership admitPart(SourceId source, StandardMessage message, Duration timeout) {
        requireReady();
        throw unsupported("pendingAdmission|error|reason:multipartAdmissionUnavailable");
    }

    @Override
    public synchronized Optional<MultipartMembership> findMultipart(SourceId source) {
        requireReady();
        return spool.find(source).map(PendingSourceRecord::multipart);
    }

    @Override
    public synchronized List<SourceId> multipartSources(UUID groupId) {
        requireReady();
        Objects.requireNonNull(groupId, "groupId");
        return List.of();
    }

    @Override
    public synchronized void makeHeldReady(Set<SourceId> sources, StandardMessage message) {
        requireReady();
        throw unsupported("pendingPublication|error|reason:heldPublicationUnavailable");
    }

    private void requireReady() {
        if (status().state() != State.READY) {
            throw new OutboundStorageException(PENDING, UNAVAILABLE, "pendingLifecycle|error|reason:notReady");
        }
    }

    private static OutboundStorageException unsupported(String message) {
        return new OutboundStorageException(PENDING, UNSUPPORTED, message);
    }

    @Override
    public synchronized void close() {
        if (state == State.CLOSED) {
            return;
        }
        try {
            spool.close();
        } finally {
            state = State.CLOSED;
        }
    }
}
