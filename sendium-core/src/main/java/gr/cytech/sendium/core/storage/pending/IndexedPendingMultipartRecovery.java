package gr.cytech.sendium.core.storage.pending;

import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.outbound.OutboundWork.SourceId;
import gr.cytech.sendium.core.storage.OutboundStage;
import gr.cytech.sendium.core.storage.OutboundStorageException;
import gr.cytech.sendium.core.storage.PendingMessageStore;
import gr.cytech.sendium.core.storage.internal.IndexedMultipartSource;
import gr.cytech.sendium.core.storage.internal.IndexedMultipartSource.Group;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

import static gr.cytech.sendium.core.storage.OutboundStage.Role.PENDING;
import static gr.cytech.sendium.core.storage.OutboundStorageException.Reason.INVALID_TRANSITION;
import static gr.cytech.sendium.core.storage.OutboundStorageException.Reason.OWNERSHIP_CONFLICT;
import static gr.cytech.sendium.core.storage.OutboundStorageException.Reason.UNAVAILABLE;
import static gr.cytech.sendium.core.storage.OutboundStorageException.Reason.UNSUPPORTED;

/** One poller and one group payload at a time; deadlines/retries remain disposable disk references. */
final class IndexedPendingMultipartRecovery<M extends StandardMessage> implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(IndexedPendingMultipartRecovery.class);
    private final PendingMessageStore<M> pending;
    private final IndexedMultipartSource index;
    private final Clock clock;
    private final ScheduledExecutorService scheduler;
    private final BiConsumer<Set<SourceId>, M> publisher;
    private ScheduledFuture<?> poller;
    private int batchSize;
    private volatile boolean ready;
    private boolean closed;

    IndexedPendingMultipartRecovery(PendingMessageStore<M> pending, IndexedMultipartSource index, Clock clock,
                                    ScheduledExecutorService scheduler, BiConsumer<Set<SourceId>, M> publisher) {
        this.pending = pending;
        this.index = index;
        this.clock = clock;
        this.scheduler = scheduler;
        this.publisher = publisher;
    }

    synchronized void reconstruct(int limit) {
        if (closed || limit <= 0) {
            throw new IllegalStateException("pendingMultipartRecovery|error|reason:invalidReconstructionState");
        }
        fail();
        batchSize = limit;
        requireBackend(); // Backend READY guarantees complete cross-record validation, not just this batch.
        for (UUID group : index.dueMultipartGroups(clock.instant(), batchSize)) {
            process(group);
        }
        poller = scheduler.scheduleWithFixedDelay(this::poll, 100, 100, TimeUnit.MILLISECONDS);
        ready = true;
    }

    boolean isReady() {
        return ready && pending.status().state() == OutboundStage.State.READY;
    }

    synchronized void accepted(SourceId source) {
        if (!isReady() || closed) {
            throw new OutboundStorageException(PENDING, UNAVAILABLE, "pendingMultipartRecovery|error|reason:notReady");
        }
        try {
            var membership = pending.findMultipart(Objects.requireNonNull(source, "source"));
            if (membership.isPresent()) {
                process(membership.orElseThrow().groupId());
            }
        } catch (RuntimeException failure) {
            if (pending.status().state() != OutboundStage.State.READY) {
                fail();
            }
            throw failure;
        }
    }

    private synchronized void poll() {
        if (closed || !ready) {
            return;
        }
        try {
            requireBackend();
            for (UUID group : index.dueMultipartGroups(clock.instant(), batchSize)) {
                try {
                    process(group);
                } catch (RuntimeException failure) {
                    var reason = failure instanceof OutboundStorageException storage ? storage.reason() : UNAVAILABLE;
                    if (!isReady()) {
                        LOGGER.warn("pendingMultipartRecovery|error|groupId:{}|reason:{}", group, reason);
                        break;
                    }
                    LOGGER.warn("pendingMultipartRecovery|retry|groupId:{}|reason:{}", group, reason);
                }
            }
        } catch (RuntimeException failure) {
            fail();
            var reason = failure instanceof OutboundStorageException storage ? storage.reason() : UNAVAILABLE;
            LOGGER.warn("pendingMultipartRecovery|error|reason:{}", reason);
        }
    }

    private void process(UUID id) {
        Optional<Group> found = index.multipartGroup(id);
        if (found.isEmpty() || !found.orElseThrow().scheduled()) {
            return;
        }
        Group group = found.orElseThrow();
        try {
            if (group.originals().size() == group.identity().key().total()) {
                var parts = new ArrayList<M>();
                for (SourceId source : group.originals()) {
                    Optional<M> part = pending.find(source);
                    if (part.isEmpty()) {
                        index.scheduleMultipartGroup(id, clock.instant());
                        return; // A terminal cleanup can race this detached snapshot; reload next batch.
                    }
                    parts.add(part.orElseThrow());
                }
                publisher.accept(Set.copyOf(group.originals()), PendingMultipartRecovery.assemble(parts));
            } else if (!group.identity().deadline().isAfter(clock.instant())) {
                for (SourceId source : group.originals()) {
                    Optional<M> part = pending.find(source);
                    if (part.isEmpty()) {
                        index.scheduleMultipartGroup(id, clock.instant());
                        return;
                    }
                    publisher.accept(Set.of(source), part.orElseThrow());
                }
            } else {
                index.scheduleMultipartGroup(id, group.identity().deadline());
                return;
            }
            index.finishMultipartGroup(id);
        } catch (RuntimeException failure) {
            if (!reconcileFailure(group, failure)) {
                throw failure;
            }
        }
    }

    /** A completed ordinal can disappear while other ordinals are publishing; that is not corruption. */
    private boolean reconcileFailure(Group expected, RuntimeException failure) {
        if (pending.status().state() != OutboundStage.State.READY) {
            fail();
            return false;
        }
        try {
            Optional<Group> latest = index.multipartGroup(expected.identity().groupId());
            if (latest.isEmpty()) {
                return true;
            }
            if (!latest.orElseThrow().originals().equals(expected.originals())) {
                index.scheduleMultipartGroup(expected.identity().groupId(), clock.instant());
                return true;
            }
            if (failure instanceof PendingRecordException || (failure instanceof OutboundStorageException storage &&
                    (storage.reason() == OWNERSHIP_CONFLICT || storage.reason() == INVALID_TRANSITION || storage.reason() == UNSUPPORTED))) {
                fail();
                return false;
            }
            index.scheduleMultipartGroup(expected.identity().groupId(), clock.instant().plusSeconds(1));
        } catch (RuntimeException reconciliationFailure) {
            if (reconciliationFailure != failure) {
                failure.addSuppressed(reconciliationFailure);
            }
            fail();
        }
        return false;
    }

    private void requireBackend() {
        if (pending.status().state() != OutboundStage.State.READY) {
            throw new OutboundStorageException(PENDING, UNAVAILABLE, "pendingMultipartRecovery|error|reason:backendNotReady");
        }
    }

    private void fail() {
        ready = false;
        if (poller != null) {
            poller.cancel(false);
            poller = null;
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        fail();
        // Scheduler and pending backend remain application-owned.
    }
}
