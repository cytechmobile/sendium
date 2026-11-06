package gr.cytech.sendium.core.storage.file;

import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.outbound.OutboundWork.Selected;
import gr.cytech.sendium.core.outbound.OutboundWork.SelectionId;
import gr.cytech.sendium.core.outbound.OutboundWork.SourceId;
import gr.cytech.sendium.core.storage.OutboundStorageException;
import gr.cytech.sendium.core.storage.PendingMessageStore;
import gr.cytech.sendium.core.storage.internal.PendingSelectionSource;
import gr.cytech.sendium.core.storage.pending.MultipartMembership;
import gr.cytech.sendium.core.storage.pending.PendingMessageData;
import gr.cytech.sendium.core.storage.pending.PendingSourceRecord;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static gr.cytech.sendium.core.storage.OutboundStage.Role.PENDING;
import static gr.cytech.sendium.core.storage.OutboundStorageException.Reason.CAPACITY_EXCEEDED;
import static gr.cytech.sendium.core.storage.OutboundStorageException.Reason.INVALID_TRANSITION;
import static gr.cytech.sendium.core.storage.OutboundStorageException.Reason.OWNERSHIP_CONFLICT;
import static gr.cytech.sendium.core.storage.OutboundStorageException.Reason.UNAVAILABLE;
import static gr.cytech.sendium.core.storage.OutboundStorageException.Reason.UNSUPPORTED;

/**
 * File-owned accepted sources using the explicit StandardMessage representation, not Java serialization.
 * Application profile enablement follows multipart recovery and ingress integration.
 */
public final class FilePendingMessageStore implements PendingMessageStore<StandardMessage>, PendingSelectionSource<StandardMessage> {
    private final PendingSpool spool;
    private final int capacity;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition changed = lock.newCondition();
    private State state = State.NEW;
    private long retained;
    private Object selectorOwner;

    public FilePendingMessageStore(Path root, int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("pendingConfiguration|error|reason:nonPositiveCapacity");
        }
        this.spool = new PendingSpool(root);
        this.capacity = capacity;
    }

    @Override
    public void open() {
        withLock(() -> {
            initialize();
            return null;
        });
    }

    private void initialize() {
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
    public Status status() {
        return withLock(() -> {
            State effective = state == State.READY && !spool.isReady() ? State.FAILED : state;
            return new Status("file", true, effective);
        });
    }

    @Override
    public SourceId admit(SourceId source, StandardMessage message) {
        return withLock(() -> admitSource(source, message, PendingSourceRecord.Disposition.READY));
    }

    @Override
    public SourceId admitHeld(SourceId source, StandardMessage message) {
        return withLock(() -> admitSource(source, message, PendingSourceRecord.Disposition.HELD));
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
    public Optional<StandardMessage> find(SourceId source) {
        return withLock(() -> {
            requireReady();
            return spool.find(source).map(record -> record.message().restore());
        });
    }

    @Override
    public List<SourceId> sourcesAfter(SourceId afterExclusive, int limit) {
        return withLock(() -> {
            requireReady();
            return spool.sourcesAfter(afterExclusive, limit);
        });
    }

    @Override
    public void complete(Set<SourceId> sources) {
        withLock(() -> {
            requireReady();
            for (SourceId source : Set.copyOf(sources)) {
                if (spool.remove(source)) {
                    retained--;
                }
            }
            return null;
        });
    }

    @Override
    public MultipartMembership admitPart(SourceId source, StandardMessage message, Duration timeout) {
        return withLock(() -> {
            requireReady();
            throw unsupported("pendingAdmission|error|reason:multipartAdmissionUnavailable");
        });
    }

    @Override
    public Optional<MultipartMembership> findMultipart(SourceId source) {
        return withLock(() -> {
            requireReady();
            return spool.find(source).map(PendingSourceRecord::multipart);
        });
    }

    @Override
    public List<SourceId> multipartSources(UUID groupId) {
        return withLock(() -> {
            requireReady();
            Objects.requireNonNull(groupId, "groupId");
            return List.of();
        });
    }

    @Override
    public void makeHeldReady(Set<SourceId> sources, StandardMessage message) {
        withLock(() -> {
            requireReady();
            throw unsupported("pendingPublication|error|reason:heldPublicationUnavailable");
        });
    }

    private void requireReady() {
        requireReady(PENDING);
    }

    @Override
    public void requireReady(Role role) {
        if (status().state() != State.READY) {
            throw new OutboundStorageException(role, UNAVAILABLE, "pendingLifecycle|error|reason:notReady");
        }
    }

    private static OutboundStorageException unsupported(String message) {
        return new OutboundStorageException(PENDING, UNSUPPORTED, message);
    }

    @Override
    public void close() {
        withLock(() -> {
            if (state != State.CLOSED) {
                try {
                    spool.close();
                } finally {
                    state = State.CLOSED;
                    changed.signalAll();
                }
            }
            return null;
        });
    }

    @Override
    public ReentrantLock selectionLock() {
        return lock;
    }

    @Override
    public Condition selectionChanged() {
        return changed;
    }

    @Override
    public void attachSelector(Object owner) {
        requireReady(Role.ROUTER_QUEUE);
        Objects.requireNonNull(owner, "owner");
        if (selectorOwner != null && selectorOwner != owner) {
            throw new OutboundStorageException(Role.ROUTER_QUEUE, OWNERSHIP_CONFLICT,
                    "pendingSelection|error|reason:alreadyOwned");
        }
        selectorOwner = owner;
    }

    @Override
    public boolean stageNext(Instant now, Consumer<Selected<StandardMessage>> publication) {
        requireReady(Role.ROUTER_QUEUE);
        List<SourceId> candidates = spool.readyCandidates(1);
        if (candidates.isEmpty()) {
            return false;
        }
        SourceId source = candidates.getFirst();
        Optional<PendingSourceRecord> found = spool.find(source);
        if (found.isEmpty()) {
            state = State.FAILED;
            throw new OutboundStorageException(Role.ROUTER_QUEUE, UNAVAILABLE,
                    "pendingSelection|error|sourceId:" + source.value() + "|reason:missingSource");
        }
        StandardMessage message = found.orElseThrow().message().restore();
        int priority = message.priority;
        publication.accept(new Selected<>(new SelectionId(UUID.randomUUID()), Set.of(source), message));
        spool.removeReady(source, priority);
        return true;
    }

    @Override
    public StandardMessage snapshot(StandardMessage message, Role role) {
        try {
            return PendingMessageData.capture(message).restore();
        } catch (OutboundStorageException failure) {
            throw new OutboundStorageException(role, failure.reason(), "pendingProjection|error|reason:unsupportedPayload", failure);
        }
    }

    @Override
    public boolean contains(SourceId source) {
        requireReady();
        return spool.find(source).isPresent();
    }

    private <T> T withLock(Supplier<T> operation) {
        lock.lock();
        try {
            return operation.get();
        } finally {
            lock.unlock();
        }
    }
}
