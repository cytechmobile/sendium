package gr.cytech.sendium.core.storage.file;

import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.outbound.OutboundWork.Selected;
import gr.cytech.sendium.core.outbound.OutboundWork.SelectionId;
import gr.cytech.sendium.core.outbound.OutboundWork.SourceId;
import gr.cytech.sendium.core.storage.OutboundStorageException;
import gr.cytech.sendium.core.storage.PendingMessageStore;
import gr.cytech.sendium.core.storage.internal.PendingSelectionSource;
import gr.cytech.sendium.core.storage.pending.MultipartKey;
import gr.cytech.sendium.core.storage.pending.MultipartMembership;
import gr.cytech.sendium.core.storage.pending.PendingMessageData;
import gr.cytech.sendium.core.storage.pending.PendingPublicationCodec.Publication;
import gr.cytech.sendium.core.storage.pending.PendingSourceRecord;
import gr.cytech.sendium.util.MessageUtil;

import java.nio.file.Path;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
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
    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition changed = lock.newCondition();
    private State state = State.NEW;
    private long retained;
    private Object selectorOwner;
    private long lastGroupOrder = -1;

    public FilePendingMessageStore(Path root, int capacity) {
        this(root, capacity, Clock.systemUTC());
    }

    public FilePendingMessageStore(Path root, int capacity, Clock clock) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("pendingConfiguration|error|reason:nonPositiveCapacity");
        }
        this.spool = new PendingSpool(root);
        this.capacity = capacity;
        this.clock = Objects.requireNonNull(clock, "clock");
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
            // Validate cross-record membership before exposing recovered sources to ingress/selection.
            SourceId cursor = null;
            while (true) {
                List<SourceId> page = spool.sourcesAfter(cursor, 128);
                if (page.isEmpty()) {
                    break;
                }
                for (SourceId source : page) {
                    MultipartMembership membership = spool.find(source).orElseThrow().multipart();
                    if (membership != null) {
                        validateMembership(membership);
                        lastGroupOrder = Math.max(lastGroupOrder, membership.groupOrder());
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
        checkCapacity(source);
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
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("pendingMultipartAdmission|error|reason:nonPositiveTimeout");
        }
        return withLock(() -> admitMultipart(source, message, timeout));
    }

    private MultipartMembership admitMultipart(SourceId source, StandardMessage message, Duration timeout) {
        requireReady();
        Optional<PendingSourceRecord> existing = spool.find(source);
        if (existing.isPresent()) {
            MultipartMembership membership = existing.orElseThrow().multipart();
            if (membership == null) {
                throw new OutboundStorageException(PENDING, INVALID_TRANSITION,
                        "pendingMultipartAdmission|error|sourceId:" + source.value() + "|reason:sourceHasNoMembership");
            }
            return membership;
        }
        checkCapacity(source);
        PendingMessageData data = PendingMessageData.capture(message);
        StandardMessage accepted = data.restore();
        MultipartKey key;
        int ordinal;
        try {
            if (!source.value().toString().equalsIgnoreCase(accepted.serial)) {
                throw new IllegalArgumentException();
            }
            key = MultipartKey.from(accepted);
            ordinal = MessageUtil.concatenation(accepted).orElseThrow().ordinal();
        } catch (IllegalArgumentException failure) {
            throw unsupported("pendingMultipartAdmission|error|sourceId:" + source.value() + "|reason:invalidIdentity");
        }
        Instant now = clock.instant();
        MultipartMembership group = openGroup(key, now);
        MultipartMembership membership;
        if (group == null) {
            if (lastGroupOrder == Long.MAX_VALUE) {
                throw unsupported("pendingMultipartAdmission|error|reason:groupOrderExhausted");
            }
            Instant deadline;
            try {
                deadline = now.plus(timeout);
            } catch (DateTimeException | ArithmeticException failure) {
                throw unsupported("pendingMultipartAdmission|error|reason:invalidDeadline");
            }
            membership = new MultipartMembership(source, UUID.randomUUID(), key, ordinal, deadline, lastGroupOrder + 1);
        } else {
            SourceId original = spool.groupOriginal(group.groupId(), ordinal);
            if (original != null) {
                MultipartMembership owner = retainedMembership(original);
                if (!sameGroup(group, owner) || owner.duplicate() || owner.ordinal() != ordinal) {
                    throw invalidMembership(original);
                }
            }
            membership = new MultipartMembership(source, group.groupId(), key, ordinal, group.deadline(), group.groupOrder(),
                    original == null ? source : original);
        }
        spool.publish(new PendingSourceRecord(source, PendingSourceRecord.Disposition.HELD, data, membership));
        retained++;
        lastGroupOrder = Math.max(lastGroupOrder, membership.groupOrder());
        if (spool.groupOriginalCount(membership.groupId()) == key.total()) {
            spool.closeGroup(membership);
        }
        return membership;
    }

    private void checkCapacity(SourceId source) {
        if (retained >= capacity) {
            throw new OutboundStorageException(PENDING, CAPACITY_EXCEEDED,
                    "pendingAdmission|error|sourceId:" + source.value() + "|reason:capacityExceeded");
        }
    }

    private MultipartMembership openGroup(MultipartKey key, Instant now) {
        String cursor = null;
        while (true) {
            List<String> page = spool.matchingGroupsAfter(key, cursor, 1);
            if (page.isEmpty()) {
                return null;
            }
            cursor = page.getFirst();
            MultipartMembership candidate = groupIdentity(MultipartPendingIndex.groupId(cursor));
            if (!candidate.key().equals(key)) {
                continue; // A matching-key hash is only a lookup hint, never identity.
            }
            if (!candidate.deadline().isAfter(now) || spool.groupOriginalCount(candidate.groupId()) == key.total()) {
                spool.closeGroup(candidate);
                continue;
            }
            return candidate;
        }
    }

    private MultipartMembership groupIdentity(UUID groupId) {
        List<SourceId> sources = spool.groupSourcesAfter(groupId, null, 1);
        if (sources.isEmpty()) {
            state = State.FAILED;
            throw new OutboundStorageException(PENDING, UNAVAILABLE,
                    "pendingMultipartRecovery|error|groupId:" + groupId + "|reason:missingGroupMembers");
        }
        return retainedMembership(sources.getFirst());
    }

    private MultipartMembership retainedMembership(SourceId source) {
        return spool.find(source).map(PendingSourceRecord::multipart).orElseThrow(() -> invalidMembership(source));
    }

    private void validateMembership(MultipartMembership member) {
        MultipartMembership identity = groupIdentity(member.groupId());
        MultipartMembership original = retainedMembership(member.originalSource());
        if (!sameGroup(identity, member) || !sameGroup(original, member) || original.duplicate() ||
                original.ordinal() != member.ordinal()) {
            throw invalidMembership(member.source());
        }
    }

    private static boolean sameGroup(MultipartMembership left, MultipartMembership right) {
        return left.groupId().equals(right.groupId()) && left.key().equals(right.key()) &&
                left.deadline().equals(right.deadline()) && left.groupOrder() == right.groupOrder();
    }

    private OutboundStorageException invalidMembership(SourceId source) {
        state = State.FAILED;
        return new OutboundStorageException(PENDING, UNAVAILABLE,
                "pendingMultipartRecovery|error|sourceId:" + source.value() + "|reason:invalidMembership");
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
            var sources = new ArrayList<SourceId>();
            SourceId cursor = null;
            while (true) {
                List<SourceId> page = spool.groupSourcesAfter(groupId, cursor, 128);
                if (page.isEmpty()) {
                    return List.copyOf(sources);
                }
                sources.addAll(page);
                cursor = page.getLast();
            }
        });
    }

    @Override
    public void makeHeldReady(Set<SourceId> sources, StandardMessage message) {
        withLock(() -> {
            requireReady();
            publishHeld(sources, message);
            return null;
        });
    }

    private void publishHeld(Set<SourceId> sources, StandardMessage message) {
        var checked = new HashSet<>(Set.copyOf(sources));
        Objects.requireNonNull(message, "message");
        if (checked.isEmpty()) {
            throw new IllegalArgumentException("pendingPublication|error|reason:emptySources");
        }
        MultipartMembership assigned = null;
        boolean unassigned = false;
        var originals = new HashSet<SourceId>();
        for (SourceId source : checked) {
            PendingSourceRecord record = spool.find(source).orElseThrow(FilePendingMessageStore::invalidPublication);
            if (record.disposition() != PendingSourceRecord.Disposition.HELD) {
                throw invalidPublication();
            }
            MultipartMembership member = record.multipart();
            if (member == null) {
                unassigned = true;
            } else {
                if (assigned != null && !sameGroup(assigned, member)) {
                    throw invalidPublication();
                }
                assigned = member;
                originals.add(member.originalSource());
            }
        }
        if (assigned != null) {
            if (unassigned || !checked.containsAll(originals) || (originals.size() > 1 && originals.size() != assigned.key().total())) {
                throw invalidPublication();
            }
            SourceId cursor = null;
            while (true) {
                List<SourceId> page = spool.groupSourcesAfter(assigned.groupId(), cursor, 128);
                if (page.isEmpty()) {
                    break;
                }
                for (SourceId source : page) {
                    if (originals.contains(retainedMembership(source).originalSource())) {
                        checked.add(source);
                    }
                }
                cursor = page.getLast();
            }
        }
        UUID existing = null;
        int bound = 0;
        for (SourceId source : checked) {
            Optional<UUID> binding = spool.publicationId(source);
            if (binding.isPresent()) {
                if (existing != null && !existing.equals(binding.orElseThrow())) {
                    throw invalidPublication();
                }
                existing = binding.orElseThrow();
                bound++;
            }
        }
        if (existing != null) {
            if (bound != checked.size()) {
                throw invalidPublication();
            }
            Publication previous = spool.findPublication(checked.iterator().next()).orElseThrow(FilePendingMessageStore::invalidPublication);
            if (!previous.sources().equals(checked)) {
                throw invalidPublication();
            }
            return;
        }
        spool.publishHeld(new Publication(UUID.randomUUID(), checked, PendingMessageData.capture(message)));
        if (assigned != null) {
            spool.closeGroup(assigned);
        }
        changed.signalAll();
    }

    private static OutboundStorageException invalidPublication() {
        return new OutboundStorageException(PENDING, INVALID_TRANSITION,
                "pendingPublication|error|reason:conflictingSourceBinding");
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
        PendingSourceRecord record = found.orElseThrow();
        StandardMessage message;
        Set<SourceId> sources;
        if (record.disposition() == PendingSourceRecord.Disposition.HELD) {
            Optional<Publication> binding = spool.findPublication(source);
            if (binding.isEmpty()) {
                state = State.FAILED;
                throw new OutboundStorageException(Role.ROUTER_QUEUE, UNAVAILABLE,
                        "pendingSelection|error|sourceId:" + source.value() + "|reason:missingPublication");
            }
            Publication work = binding.orElseThrow();
            message = work.message().restore();
            sources = work.sources();
        } else {
            message = record.message().restore();
            sources = Set.of(source);
        }
        int priority = message.priority;
        publication.accept(new Selected<>(new SelectionId(UUID.randomUUID()), sources, message));
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
