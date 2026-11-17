package gr.cytech.sendium.core.storage.file;

import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.outbound.OutboundWork.Selected;
import gr.cytech.sendium.core.outbound.OutboundWork.SelectionId;
import gr.cytech.sendium.core.storage.OutboundStorageException;
import gr.cytech.sendium.core.storage.SelectedRouterStore;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static gr.cytech.sendium.core.storage.OutboundStorageException.Reason.INVALID_TRANSITION;
import static gr.cytech.sendium.core.storage.OutboundStorageException.Reason.UNAVAILABLE;

/** Durable selected executions; only the bounded routing window has runtime entries. */
public final class FileSelectedRouterStore implements SelectedRouterStore<StandardMessage> {
    private final FilePendingMessageStore pending;
    private final int capacity;
    private final Clock clock;
    private final Map<SelectionId, Entry> active = new HashMap<>();
    private final ArrayDeque<Entry> ready = new ArrayDeque<>();
    private State state = State.NEW;
    private PendingSpool spool;

    public FileSelectedRouterStore(FilePendingMessageStore pending, int capacity) {
        this(pending, capacity, Clock.systemUTC());
    }

    public FileSelectedRouterStore(FilePendingMessageStore pending, int capacity, Clock clock) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("selectedConfiguration|error|reason:nonPositiveCapacity");
        }
        this.pending = Objects.requireNonNull(pending, "pending");
        this.capacity = capacity;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void open() {
        pending.selectionLock().lock();
        try {
            if (state == State.READY) {
                requireReady();
                return;
            }
            if (state != State.NEW) {
                throw invalid("cannotReopen");
            }
            pending.attachSelector(this);
            spool = pending.selectedSpool();
            state = State.READY;
            refill();
        } catch (RuntimeException failure) {
            state = State.FAILED;
            throw failure;
        } finally {
            pending.selectionLock().unlock();
        }
    }

    @Override
    public Status status() {
        pending.selectionLock().lock();
        try {
            State effective = state == State.READY && pending.status().state() != State.READY ? State.FAILED : state;
            return new Status("file", true, effective);
        } finally {
            pending.selectionLock().unlock();
        }
    }

    @Override
    public int selectToRouter(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("selectedSelection|error|reason:nonPositiveLimit");
        }
        pending.selectionLock().lock();
        try {
            requireReady();
            refill();
            int count = 0;
            while (count < limit && active.size() < capacity) {
                if (!pending.stageNext(clock.instant(), spool::publishSelected)) {
                    break;
                }
                count++;
                refill();
            }
            pending.selectionChanged().signalAll();
            return count;
        } catch (RuntimeException failure) {
            if (pending.status().state() != State.READY) {
                state = State.FAILED;
            }
            throw failure;
        } finally {
            pending.selectionLock().unlock();
        }
    }

    @Override
    public Optional<Selected<StandardMessage>> take(Duration timeout) throws InterruptedException {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("selectedTake|error|reason:negativeTimeout");
        }
        long remaining;
        try {
            remaining = timeout.toNanos();
        } catch (ArithmeticException overflow) {
            remaining = Long.MAX_VALUE;
        }
        pending.selectionLock().lockInterruptibly();
        try {
            while (true) {
                requireReady();
                refill();
                Entry entry = ready.peekFirst();
                if (entry != null) {
                    Selected<StandardMessage> projection = new Selected<>(entry.selected.id(), entry.selected.sources(),
                            pending.snapshot(entry.selected.message(), Role.ROUTER_QUEUE));
                    ready.removeFirst();
                    entry.inFlight = projection;
                    return Optional.of(projection);
                }
                if (remaining <= 0) {
                    return Optional.empty();
                }
                remaining = pending.selectionChanged().awaitNanos(remaining);
            }
        } finally {
            pending.selectionLock().unlock();
        }
    }

    @Override
    public void release(Selected<StandardMessage> selected) {
        Objects.requireNonNull(selected, "selected");
        pending.selectionLock().lock();
        try {
            requireReady();
            Entry entry = active.get(selected.id());
            if (entry != null && entry.lastReturned == selected) {
                return;
            }
            if (entry == null || entry.inFlight != selected || !entry.selected.sources().equals(selected.sources())) {
                throw invalid("notCurrentTake");
            }
            entry.selected = new Selected<>(selected.id(), selected.sources(), pending.snapshot(selected.message(), Role.ROUTER_QUEUE));
            entry.inFlight = null;
            entry.lastReturned = selected;
            ready.addLast(entry);
            pending.selectionChanged().signalAll();
        } finally {
            pending.selectionLock().unlock();
        }
    }

    @Override
    public void markRouted(SelectionId selection) {
        Objects.requireNonNull(selection, "selection");
        pending.selectionLock().lock();
        try {
            requireReady();
            Entry entry = active.get(selection);
            if (entry == null) {
                // A retained, unscheduled record is already routed in this process.
                if (spool.findSelected(selection).isPresent() && !spool.selectedScheduled(selection)) {
                    return;
                }
                throw invalid("notTaken");
            }
            if (entry.inFlight == null) {
                throw invalid("notTaken");
            }
            active.remove(selection);
            pending.selectionChanged().signalAll();
        } finally {
            pending.selectionLock().unlock();
        }
    }

    @Override
    public void complete(SelectionId selection) {
        Objects.requireNonNull(selection, "selection");
        pending.selectionLock().lock();
        try {
            requireReady();
            if (spool.findSelected(selection).isPresent()) {
                throw invalid("completePendingSourcesFirst");
            }
            Entry entry = active.remove(selection);
            ready.remove(entry);
            pending.selectionChanged().signalAll();
        } finally {
            pending.selectionLock().unlock();
        }
    }

    private void refill() {
        int space = capacity - active.size();
        if (space <= 0) {
            return;
        }
        for (SelectionId id : spool.selectedCandidates(space)) {
            var selected = spool.findSelected(id).orElseThrow(() -> invalid("missingScheduledRecord"));
            spool.unscheduleSelected(id);
            Entry entry = new Entry(selected);
            active.put(id, entry);
            ready.addLast(entry);
        }
    }

    private void requireReady() {
        if (state != State.READY) {
            throw new OutboundStorageException(Role.ROUTER_QUEUE, UNAVAILABLE, "selectedLifecycle|error|reason:notReady");
        }
        pending.requireReady(Role.ROUTER_QUEUE);
    }

    private static OutboundStorageException invalid(String reason) {
        return new OutboundStorageException(Role.ROUTER_QUEUE, INVALID_TRANSITION, "selectedTransition|error|reason:" + reason);
    }

    @Override
    public void close() {
        pending.selectionLock().lock();
        try {
            state = State.CLOSED;
            active.clear();
            ready.clear();
            pending.selectionChanged().signalAll();
        } finally {
            pending.selectionLock().unlock();
        }
    }

    private static final class Entry {
        private Selected<StandardMessage> selected;
        private Selected<StandardMessage> inFlight;
        private Selected<StandardMessage> lastReturned;

        private Entry(Selected<StandardMessage> selected) {
            this.selected = selected;
        }
    }
}
