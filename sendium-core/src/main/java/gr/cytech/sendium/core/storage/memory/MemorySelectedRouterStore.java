package gr.cytech.sendium.core.storage.memory;

import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.outbound.OutboundWork.Selected;
import gr.cytech.sendium.core.outbound.OutboundWork.SelectionId;
import gr.cytech.sendium.core.outbound.OutboundWork.SourceId;
import gr.cytech.sendium.core.storage.OutboundStorageException;
import gr.cytech.sendium.core.storage.SelectedRouterStore;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static gr.cytech.sendium.core.storage.OutboundStorageException.Reason.INVALID_TRANSITION;
import static gr.cytech.sendium.core.storage.OutboundStorageException.Reason.UNAVAILABLE;

/**
 * One selected-router owner per pending-store lifetime. A shared lock makes memory selection and
 * publication indivisible per ready item to other operations. Capacity includes queued and taken routing work;
 * routed records remain retained until source cleanup but no longer occupy a routing slot.
 */
public final class MemorySelectedRouterStore<M extends StandardMessage> implements SelectedRouterStore<M> {
    private final MemoryPendingMessageStore<M> pending;
    private final int capacity;
    private final Clock clock;
    private final Map<SelectionId, Entry<M>> records = new HashMap<>();
    private final ArrayDeque<Entry<M>> ready = new ArrayDeque<>();
    private State state = State.NEW;
    private int routingSlots;

    public MemorySelectedRouterStore(MemoryPendingMessageStore<M> pending, int capacity) {
        this(pending, capacity, Clock.systemUTC());
    }

    public MemorySelectedRouterStore(MemoryPendingMessageStore<M> pending, int capacity, Clock clock) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Router capacity must be positive");
        }
        this.pending = Objects.requireNonNull(pending, "pending");
        this.capacity = capacity;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void open() {
        pending.lock.lock();
        try {
            if (state == State.CLOSED) {
                throw invalid("Closed memory selected-router store cannot reopen");
            }
            pending.attachSelector(this);
            state = State.READY;
        } finally {
            pending.lock.unlock();
        }
    }

    @Override
    public Status status() {
        pending.lock.lock();
        try {
            State effective = state == State.READY && pending.status().state() != State.READY ? State.FAILED : state;
            return new Status("memory", false, effective);
        } finally {
            pending.lock.unlock();
        }
    }

    @Override
    public int selectAndStage(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("Selection limit must be positive");
        }
        pending.lock.lock();
        try {
            requireReady();
            int budget = Math.min(limit, capacity - routingSlots);
            int staged = 0;
            Instant now = clock.instant();
            while (staged < budget) {
                MemoryPendingMessageStore.Entry<M> source = pending.nextEligible(now);
                if (source == null) {
                    break;
                }
                M message = pending.snapshot(source.message, Role.ROUTER_QUEUE);
                Entry<M> entry = new Entry<>(new SelectionId(UUID.randomUUID()), source.sources, message);
                records.put(entry.id, entry);
                ready.addLast(entry);
                pending.removeWaiting(source);
                routingSlots++;
                staged++;
                pending.changed.signalAll();
            }
            return staged;
        } finally {
            pending.lock.unlock();
        }
    }

    @Override
    public Optional<Selected<M>> take(Duration timeout) throws InterruptedException {
        long remaining = timeoutNanos(timeout);
        pending.lock.lockInterruptibly();
        try {
            while (true) {
                requireReady();
                Entry<M> entry = ready.peekFirst();
                if (entry != null) {
                    M message = pending.snapshot(entry.message, Role.ROUTER_QUEUE);
                    Selected<M> projection = new Selected<>(entry.id, entry.sources, message);
                    ready.removeFirst();
                    entry.phase = Phase.TAKEN;
                    entry.inFlight = projection;
                    return Optional.of(projection);
                }
                if (remaining <= 0) {
                    return Optional.empty();
                }
                remaining = pending.changed.awaitNanos(remaining);
            }
        } finally {
            pending.lock.unlock();
        }
    }

    /** Return the take result itself; it identifies the current in-process routing attempt. */
    @Override
    public void release(Selected<M> selected) {
        Objects.requireNonNull(selected, "selected");
        pending.lock.lock();
        try {
            requireReady();
            Entry<M> entry = requireEntry(selected.id());
            if (entry.lastReturned == selected) {
                return;
            }
            if (entry.phase != Phase.TAKEN || entry.inFlight != selected || !entry.sources.equals(selected.sources())) {
                throw invalid("Only the current routing take can be returned");
            }
            M snapshot = pending.snapshot(selected.message(), Role.ROUTER_QUEUE);
            entry.message = snapshot;
            entry.phase = Phase.QUEUED;
            entry.inFlight = null;
            entry.lastReturned = selected;
            ready.addLast(entry);
            pending.changed.signalAll();
        } finally {
            pending.lock.unlock();
        }
    }

    @Override
    public void markRouted(SelectionId selection) {
        Objects.requireNonNull(selection, "selection");
        pending.lock.lock();
        try {
            requireReady();
            Entry<M> entry = requireEntry(selection);
            if (entry.phase == Phase.ROUTED) {
                return;
            }
            if (entry.phase != Phase.TAKEN) {
                throw invalid("Only taken routing work can be marked routed");
            }
            entry.phase = Phase.ROUTED;
            entry.inFlight = null;
            entry.lastReturned = null;
            routingSlots--;
        } finally {
            pending.lock.unlock();
        }
    }

    @Override
    public void complete(SelectionId selection) {
        Objects.requireNonNull(selection, "selection");
        pending.lock.lock();
        try {
            requireReady();
            Entry<M> entry = records.get(selection);
            if (entry == null) {
                return;
            }
            if (entry.sources.stream().anyMatch(pending::contains)) {
                throw invalid("Complete pending sources before removing selected ownership");
            }
            records.remove(selection);
            ready.remove(entry);
            if (entry.phase != Phase.ROUTED) {
                routingSlots--;
            }
        } finally {
            pending.lock.unlock();
        }
    }

    @Override
    public void close() {
        pending.lock.lock();
        try {
            state = State.CLOSED;
            records.clear();
            ready.clear();
            routingSlots = 0;
            pending.changed.signalAll();
        } finally {
            pending.lock.unlock();
        }
    }

    private void requireReady() {
        if (state != State.READY) {
            throw new OutboundStorageException(Role.ROUTER_QUEUE, UNAVAILABLE, "Memory selected-router store is not ready");
        }
        pending.requireReady(Role.ROUTER_QUEUE);
    }

    private Entry<M> requireEntry(SelectionId selection) {
        Entry<M> entry = records.get(selection);
        if (entry == null) {
            throw invalid("Unknown selection");
        }
        return entry;
    }

    private static OutboundStorageException invalid(String message) {
        return new OutboundStorageException(Role.ROUTER_QUEUE, INVALID_TRANSITION, message);
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
        QUEUED, TAKEN, ROUTED
    }

    private static final class Entry<M extends StandardMessage> {
        private final SelectionId id;
        private final Set<SourceId> sources;
        private M message;
        private Phase phase = Phase.QUEUED;
        private Selected<M> inFlight;
        private Selected<M> lastReturned;

        private Entry(SelectionId id, Set<SourceId> sources, M message) {
            this.id = id;
            this.sources = sources;
            this.message = message;
        }
    }
}
