package gr.cytech.sendium.core.storage.memory;

import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.outbound.OutboundWork.SourceId;
import gr.cytech.sendium.core.storage.OutboundStorageException;
import gr.cytech.sendium.core.storage.PendingMessageStore;
import gr.cytech.sendium.core.storage.pending.MultipartKey;
import gr.cytech.sendium.core.storage.pending.MultipartMembership;
import gr.cytech.sendium.util.MessageUtil;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.function.UnaryOperator;

import static gr.cytech.sendium.core.storage.OutboundStorageException.Reason.CAPACITY_EXCEEDED;
import static gr.cytech.sendium.core.storage.OutboundStorageException.Reason.INVALID_TRANSITION;
import static gr.cytech.sendium.core.storage.OutboundStorageException.Reason.OWNERSHIP_CONFLICT;
import static gr.cytech.sendium.core.storage.OutboundStorageException.Reason.UNAVAILABLE;
import static gr.cytech.sendium.core.storage.OutboundStorageException.Reason.UNSUPPORTED;

/**
 * Instance-local accepted state with a bounded source count. Snapshot functions must preserve the
 * concrete message type and detach all mutable fields. Snapshot and eligibility functions run under
 * the store lock and must be side-effect-free, nonblocking, and must not call back into these stores.
 */
public final class MemoryPendingMessageStore<M extends StandardMessage> implements PendingMessageStore<M> {
    final ReentrantLock lock = new ReentrantLock();
    final Condition changed = lock.newCondition();

    private final int capacity;
    private final UnaryOperator<M> snapshots;
    private final Function<? super M, Instant> eligibleAt;
    private final Clock clock;
    private final NavigableMap<SourceId, Source<M>> records = new TreeMap<>(Comparator.comparing(SourceId::value));
    private final Map<MultipartKey, List<MultipartGroup>> openGroups = new HashMap<>();
    private final NavigableMap<Integer, NavigableSet<Entry<M>>> waiting = new TreeMap<>(Comparator.reverseOrder());
    private State state = State.NEW;
    private Object selectorOwner;
    private long sequence;
    private long multipartSequence;

    public MemoryPendingMessageStore(int capacity, UnaryOperator<M> snapshots) {
        this(capacity, snapshots, message -> Instant.MIN);
    }

    /** Eligibility is evaluated when ready work is first published; held admission does not evaluate it. */
    public MemoryPendingMessageStore(int capacity, UnaryOperator<M> snapshots, Function<? super M, Instant> eligibleAt) {
        this(capacity, snapshots, eligibleAt, Clock.systemUTC());
    }

    public MemoryPendingMessageStore(int capacity, UnaryOperator<M> snapshots, Function<? super M, Instant> eligibleAt, Clock clock) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Pending capacity must be positive");
        }
        this.capacity = capacity;
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.eligibleAt = Objects.requireNonNull(eligibleAt, "eligibleAt");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void open() {
        lock.lock();
        try {
            if (state == State.CLOSED) {
                throw new OutboundStorageException(Role.PENDING, INVALID_TRANSITION, "Closed memory pending store cannot reopen");
            }
            state = State.READY;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Status status() {
        lock.lock();
        try {
            return new Status("memory", false, state);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public SourceId admit(SourceId source, M message) {
        return admit(source, message, false);
    }

    private SourceId admit(SourceId source, M message, boolean held) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(message, "message");
        lock.lock();
        try {
            requireReady(Role.PENDING);
            if (records.containsKey(source)) {
                return source;
            }
            if (records.size() >= capacity) {
                throw new OutboundStorageException(Role.PENDING, CAPACITY_EXCEEDED, "Memory pending capacity reached");
            }
            M snapshot = snapshot(message, Role.PENDING);
            Source<M> accepted = new Source<>(snapshot, held);
            Entry<M> ready = held ? null : readyEntry(Set.of(source), snapshot);
            records.put(source, accepted);
            if (ready != null) {
                accepted.ready = ready;
                addWaiting(ready);
            }
            return source;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public SourceId admitHeld(SourceId source, M message) {
        return admit(source, message, true);
    }

    @Override
    public MultipartMembership admitPart(SourceId source, M message, Duration timeout) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("Multipart timeout must be positive");
        }
        lock.lock();
        try {
            requireReady(Role.PENDING);
            Source<M> existing = records.get(source);
            if (existing != null) {
                if (existing.membership == null) {
                    throw new OutboundStorageException(Role.PENDING, INVALID_TRANSITION, "Source has no multipart membership");
                }
                return existing.membership;
            }
            if (records.size() >= capacity) {
                throw new OutboundStorageException(Role.PENDING, CAPACITY_EXCEEDED, "Memory pending capacity reached");
            }
            M snapshot = snapshot(message, Role.PENDING);
            MultipartKey key;
            int ordinal;
            try {
                if (!source.value().toString().equalsIgnoreCase(snapshot.serial)) {
                    throw new IllegalArgumentException("Multipart source identity mismatch");
                }
                key = MultipartKey.from(snapshot);
                ordinal = MessageUtil.concatenation(snapshot).orElseThrow().ordinal();
            } catch (IllegalArgumentException failure) {
                throw new OutboundStorageException(Role.PENDING, UNSUPPORTED, "Unsupported multipart identity");
            }
            Instant now = clock.instant();
            var groups = openGroups.getOrDefault(key, new ArrayList<>());
            groups.removeIf(group -> !group.deadline.isAfter(now));
            MultipartGroup chosen = null;
            for (MultipartGroup group : groups) {
                if (!group.ordinals.get(ordinal)) {
                    chosen = group;
                    break;
                }
            }
            if (chosen == null) {
                chosen = new MultipartGroup(key, now.plus(timeout), multipartSequence++);
                groups.add(chosen);
            }
            var membership = new MultipartMembership(source, chosen.id, key, ordinal, chosen.deadline, chosen.order);
            records.put(source, new Source<>(snapshot, membership, chosen));
            openGroups.put(key, groups);
            chosen.ordinals.set(ordinal);
            if (chosen.ordinals.cardinality() == key.total()) {
                closeGroup(chosen);
            }
            return membership;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Optional<MultipartMembership> findMultipart(SourceId source) {
        Objects.requireNonNull(source, "source");
        lock.lock();
        try {
            requireReady(Role.PENDING);
            Source<M> accepted = records.get(source);
            return accepted == null ? Optional.empty() : Optional.ofNullable(accepted.membership);
        } finally {
            lock.unlock();
        }
    }

    private void closeGroup(MultipartGroup group) {
        if (group == null) {
            return;
        }
        List<MultipartGroup> groups = openGroups.get(group.key);
        if (groups != null) {
            groups.remove(group);
            if (groups.isEmpty()) {
                openGroups.remove(group.key);
            }
        }
    }

    @Override
    public void makeHeldReady(Set<SourceId> sources, M message) {
        Set<SourceId> checked = Set.copyOf(sources);
        Objects.requireNonNull(message, "message");
        if (checked.isEmpty()) {
            throw new IllegalArgumentException("Ready work must reference at least one source");
        }
        lock.lock();
        try {
            requireReady(Role.PENDING);
            Entry<M> existing = null;
            MultipartGroup assignedGroup = null;
            for (SourceId source : checked) {
                Source<M> accepted = records.get(source);
                if (accepted == null || !accepted.initiallyHeld) {
                    throw invalidPublication();
                }
                if (accepted.group != null) {
                    if (assignedGroup != null && assignedGroup != accepted.group) {
                        throw invalidPublication();
                    }
                    assignedGroup = accepted.group;
                }
                if (accepted.ready != null) {
                    if (existing != null && accepted.ready != existing) {
                        throw invalidPublication();
                    }
                    existing = accepted.ready;
                }
            }
            if (assignedGroup != null && checked.size() > 1) {
                if (checked.size() != assignedGroup.key.total()) {
                    throw invalidPublication();
                }
                for (SourceId source : checked) {
                    if (records.get(source).group != assignedGroup) {
                        throw invalidPublication();
                    }
                }
            }
            if (existing != null) {
                if (!existing.sources.equals(checked)) {
                    throw invalidPublication();
                }
                for (SourceId source : checked) {
                    if (records.get(source).ready != existing) {
                        throw invalidPublication();
                    }
                }
                return;
            }
            Entry<M> entry = readyEntry(checked, snapshot(message, Role.PENDING));
            for (SourceId source : checked) {
                records.get(source).ready = entry;
                closeGroup(records.get(source).group);
            }
            addWaiting(entry);
        } finally {
            lock.unlock();
        }
    }

    private Entry<M> readyEntry(Set<SourceId> sources, M message) {
        Instant available;
        try {
            available = Objects.requireNonNull(eligibleAt.apply(message), "eligibleAt result");
        } catch (RuntimeException failure) {
            throw new OutboundStorageException(Role.PENDING, UNSUPPORTED, "Cannot determine work eligibility", failure);
        }
        return new Entry<>(sources, message, available, sequence++);
    }

    private void addWaiting(Entry<M> entry) {
        waiting.computeIfAbsent(entry.priority, ignored -> new TreeSet<>(Comparator
                .comparing((Entry<M> item) -> item.available)
                .thenComparingLong(item -> item.sequence))).add(entry);
    }

    private static OutboundStorageException invalidPublication() {
        return new OutboundStorageException(Role.PENDING, INVALID_TRANSITION,
                "Ready publication requires retained held sources with one matching source set");
    }

    @Override
    public Optional<M> find(SourceId source) {
        Objects.requireNonNull(source, "source");
        lock.lock();
        try {
            requireReady(Role.PENDING);
            Source<M> entry = records.get(source);
            return entry == null ? Optional.empty() : Optional.of(snapshot(entry.message, Role.PENDING));
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<SourceId> sourcesAfter(SourceId afterExclusive, int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("Recovery page limit must be positive");
        }
        lock.lock();
        try {
            requireReady(Role.PENDING);
            var remaining = afterExclusive == null ? records : records.tailMap(afterExclusive, false);
            var page = new ArrayList<SourceId>();
            for (SourceId source : remaining.navigableKeySet()) {
                if (page.size() == limit) {
                    break;
                }
                page.add(source);
            }
            return List.copyOf(page);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void complete(Set<SourceId> sources) {
        Set<SourceId> checked = Set.copyOf(sources);
        lock.lock();
        try {
            requireReady(Role.PENDING);
            for (SourceId source : checked) {
                Source<M> entry = records.remove(source);
                if (entry != null) {
                    closeGroup(entry.group);
                    if (entry.ready != null) {
                        removeWaiting(entry.ready);
                    }
                }
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void close() {
        lock.lock();
        try {
            state = State.CLOSED;
            records.clear();
            openGroups.clear();
            waiting.clear();
            changed.signalAll();
        } finally {
            lock.unlock();
        }
    }

    void requireReady(Role operation) {
        if (state != State.READY) {
            throw new OutboundStorageException(operation, UNAVAILABLE, "Memory pending store is not ready");
        }
    }

    void attachSelector(Object owner) {
        requireReady(Role.ROUTER_QUEUE);
        if (selectorOwner != null && selectorOwner != owner) {
            throw new OutboundStorageException(Role.ROUTER_QUEUE, OWNERSHIP_CONFLICT,
                    "Memory pending store already has a selected-router owner");
        }
        selectorOwner = owner;
    }

    Entry<M> nextEligible(Instant now) {
        for (NavigableSet<Entry<M>> priority : waiting.values()) {
            Entry<M> candidate = priority.first();
            if (!candidate.available.isAfter(now)) {
                return candidate;
            }
        }
        return null;
    }

    void removeWaiting(Entry<M> entry) {
        NavigableSet<Entry<M>> priority = waiting.get(entry.priority);
        if (priority != null) {
            priority.remove(entry);
            if (priority.isEmpty()) {
                waiting.remove(entry.priority);
            }
        }
    }

    boolean contains(SourceId source) {
        return records.containsKey(source);
    }

    M snapshot(M message, Role operation) {
        M copy;
        try {
            copy = snapshots.apply(message);
        } catch (RuntimeException failure) {
            throw new OutboundStorageException(operation, UNAVAILABLE, "Cannot snapshot memory work", failure);
        }
        if (copy == null || copy == message || copy.getClass() != message.getClass()) {
            throw new OutboundStorageException(operation, UNSUPPORTED,
                    "Memory snapshots must be independent and preserve the concrete message type");
        }
        return copy;
    }

    static final class Entry<M extends StandardMessage> {
        final Set<SourceId> sources;
        final M message;
        final int priority;
        final Instant available;
        final long sequence;

        Entry(Set<SourceId> sources, M message, Instant available, long sequence) {
            this.sources = sources;
            this.message = message;
            this.priority = message.priority;
            this.available = available;
            this.sequence = sequence;
        }
    }

    private static final class Source<M extends StandardMessage> {
        private final M message;
        private final boolean initiallyHeld;
        private final MultipartMembership membership;
        private final MultipartGroup group;
        private Entry<M> ready;

        private Source(M message, boolean held) {
            this.message = message;
            this.initiallyHeld = held;
            this.membership = null;
            this.group = null;
        }

        private Source(M message, MultipartMembership membership, MultipartGroup group) {
            this.message = message;
            this.initiallyHeld = true;
            this.membership = membership;
            this.group = group;
        }
    }

    private static final class MultipartGroup {
        private final UUID id = UUID.randomUUID();
        private final MultipartKey key;
        private final Instant deadline;
        private final long order;
        private final BitSet ordinals = new BitSet();

        private MultipartGroup(MultipartKey key, Instant deadline, long order) {
            this.key = key;
            this.deadline = deadline;
            this.order = order;
        }
    }
}
