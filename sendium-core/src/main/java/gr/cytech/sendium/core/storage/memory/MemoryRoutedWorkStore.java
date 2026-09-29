package gr.cytech.sendium.core.storage.memory;

import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.outbound.OutboundWork.Assignment;
import gr.cytech.sendium.core.outbound.OutboundWork.Completed;
import gr.cytech.sendium.core.outbound.OutboundWork.Routed;
import gr.cytech.sendium.core.outbound.OutboundWork.SelectionId;
import gr.cytech.sendium.core.outbound.OutboundWork.SourceId;
import gr.cytech.sendium.core.outbound.OutboundWork.WorkId;
import gr.cytech.sendium.core.storage.OutboundStorageException;
import gr.cytech.sendium.core.storage.RoutedWorkStore;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.UnaryOperator;

import static gr.cytech.sendium.core.storage.OutboundStorageException.Reason.CAPACITY_EXCEEDED;
import static gr.cytech.sendium.core.storage.OutboundStorageException.Reason.INVALID_TRANSITION;
import static gr.cytech.sendium.core.storage.OutboundStorageException.Reason.UNAVAILABLE;
import static gr.cytech.sendium.core.storage.OutboundStorageException.Reason.UNSUPPORTED;

/**
 * Single-destination execution with one work item per selection. Capacity counts retained
 * selections, including terminal selections awaiting forget. Snapshots must detach mutable fields,
 * preserve subtype/data, and be nonblocking, side-effect-free, and non-reentrant.
 */
public final class MemoryRoutedWorkStore<M extends StandardMessage> implements RoutedWorkStore<M> {
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition changed = lock.newCondition();
    private final int capacity;
    private final UnaryOperator<M> snapshots;
    private final Map<SelectionId, Entry<M>> selections = new HashMap<>();
    private final Map<SourceId, SelectionId> sourceOwners = new HashMap<>();
    private final Map<WorkId, Entry<M>> work = new HashMap<>();
    private final Map<String, ArrayDeque<Entry<M>>> ready = new HashMap<>();
    private State state = State.NEW;

    public MemoryRoutedWorkStore(int capacity, UnaryOperator<M> snapshots) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Routed selection capacity must be positive");
        }
        this.capacity = capacity;
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
    }

    @Override
    public void open() {
        lock.lock();
        try {
            if (state == State.CLOSED) {
                throw invalid("Closed memory routed-work store cannot reopen");
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
    public Routed<M> record(Assignment<M> assignment) {
        Objects.requireNonNull(assignment, "assignment");
        lock.lock();
        try {
            requireReady();
            Entry<M> existing = selections.get(assignment.selection());
            if (existing != null) {
                if (!existing.sources.equals(assignment.sources()) ||
                        !existing.destination.equals(assignment.destination().name())) {
                    throw invalid("An existing selection cannot change sources or destination");
                }
                return projection(existing, existing.original);
            }
            if (assignment.sources().stream().anyMatch(sourceOwners::containsKey)) {
                throw invalid("A source already belongs to another routed selection");
            }
            if (selections.size() >= capacity) {
                throw new OutboundStorageException(Role.ROUTED_WORK, CAPACITY_EXCEEDED, "Memory routed selection capacity reached");
            }
            Entry<M> entry = new Entry<>(new WorkId(UUID.randomUUID()), assignment.selection(), assignment.sources(),
                    assignment.destination().name(), snapshot(assignment.destination().message()));
            final Routed<M> result = projection(entry, entry.original);
            selections.put(entry.selection, entry);
            entry.sources.forEach(source -> sourceOwners.put(source, entry.selection));
            work.put(entry.id, entry);
            enqueue(entry);
            return result;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Optional<Routed<M>> take(String destination, Duration timeout) throws InterruptedException {
        requireDestination(destination);
        long remaining = timeoutNanos(timeout);
        lock.lockInterruptibly();
        try {
            while (true) {
                requireReady();
                ArrayDeque<Entry<M>> queue = ready.get(destination);
                if (queue != null) {
                    Entry<M> entry = queue.peekFirst();
                    final Routed<M> result = projection(entry, entry.message);
                    queue.removeFirst();
                    if (queue.isEmpty()) {
                        ready.remove(destination);
                    }
                    entry.phase = Phase.TAKEN;
                    entry.inFlight = result;
                    return Optional.of(result);
                }
                if (remaining <= 0) {
                    return Optional.empty();
                }
                remaining = changed.awaitNanos(remaining);
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void release(Routed<M> taken) {
        Objects.requireNonNull(taken, "taken");
        lock.lock();
        try {
            requireReady();
            Entry<M> entry = requireEntry(taken.id());
            if (entry.lastReturned == taken) {
                return;
            }
            if (entry.phase != Phase.TAKEN || entry.inFlight != taken) {
                throw invalid("Only the current destination take can be returned");
            }
            M message = snapshot(taken.message());
            entry.message = message;
            entry.phase = Phase.QUEUED;
            entry.inFlight = null;
            entry.lastReturned = taken;
            enqueue(entry);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Completed complete(WorkId id) {
        Objects.requireNonNull(id, "id");
        lock.lock();
        try {
            requireReady();
            Entry<M> entry = requireEntry(id);
            if (entry.phase == Phase.COMPLETED) {
                return entry.completed;
            }
            if (entry.phase != Phase.TAKEN) {
                throw invalid("Only taken destination work can complete");
            }
            entry.completed = new Completed(entry.selection, entry.sources);
            entry.phase = Phase.COMPLETED;
            entry.message = null;
            entry.inFlight = null;
            entry.lastReturned = null;
            return entry.completed;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void forget(SelectionId id) {
        Objects.requireNonNull(id, "id");
        lock.lock();
        try {
            requireReady();
            Entry<M> entry = selections.get(id);
            if (entry == null) {
                return;
            }
            if (entry.completed == null) {
                throw invalid("Nonterminal routed work cannot be forgotten");
            }
            work.remove(entry.id);
            entry.sources.forEach(sourceOwners::remove);
            selections.remove(id);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void close() {
        lock.lock();
        try {
            state = State.CLOSED;
            selections.clear();
            sourceOwners.clear();
            work.clear();
            ready.clear();
            changed.signalAll();
        } finally {
            lock.unlock();
        }
    }

    private void enqueue(Entry<M> entry) {
        ready.computeIfAbsent(entry.destination, ignored -> new ArrayDeque<>()).addLast(entry);
        changed.signalAll();
    }

    private Routed<M> projection(Entry<M> entry, M message) {
        return new Routed<>(entry.id, entry.selection, entry.destination, snapshot(message));
    }

    private M snapshot(M message) {
        M copy;
        try {
            copy = snapshots.apply(message);
        } catch (RuntimeException failure) {
            throw new OutboundStorageException(Role.ROUTED_WORK, UNAVAILABLE, "Cannot snapshot routed memory work", failure);
        }
        if (copy == null || copy == message || copy.getClass() != message.getClass()) {
            throw new OutboundStorageException(Role.ROUTED_WORK, UNSUPPORTED,
                    "Memory snapshots must be independent and preserve the concrete message type");
        }
        return copy;
    }

    private void requireReady() {
        if (state != State.READY) {
            throw new OutboundStorageException(Role.ROUTED_WORK, UNAVAILABLE, "Memory routed-work store is not ready");
        }
    }

    private Entry<M> requireEntry(WorkId id) {
        Entry<M> entry = work.get(id);
        if (entry == null) {
            throw invalid("Unknown routed work");
        }
        return entry;
    }

    private static OutboundStorageException invalid(String message) {
        return new OutboundStorageException(Role.ROUTED_WORK, INVALID_TRANSITION, message);
    }

    private static void requireDestination(String destination) {
        Objects.requireNonNull(destination, "destination");
        if (destination.isBlank()) {
            throw new IllegalArgumentException("Destination must not be blank");
        }
    }

    private static long timeoutNanos(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("Timeout must not be negative");
        }
        try {
            return timeout.toNanos();
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    private enum Phase {
        QUEUED, TAKEN, COMPLETED
    }

    private static final class Entry<M extends StandardMessage> {
        private final WorkId id;
        private final SelectionId selection;
        private final Set<SourceId> sources;
        private final String destination;
        private final M original;
        private M message;
        private Phase phase = Phase.QUEUED;
        private Completed completed;
        private Routed<M> inFlight;
        private Routed<M> lastReturned;

        private Entry(WorkId id, SelectionId selection, Set<SourceId> sources, String destination, M original) {
            this.id = id;
            this.selection = selection;
            this.sources = sources;
            this.destination = destination;
            this.original = original;
            this.message = original;
        }
    }
}
