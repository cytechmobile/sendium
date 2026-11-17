package gr.cytech.sendium.core.storage.file;

import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.outbound.OutboundWork.Selected;
import gr.cytech.sendium.core.outbound.OutboundWork.SelectionId;
import gr.cytech.sendium.core.storage.OutboundStorageException;
import gr.cytech.sendium.core.storage.SelectedRouterStore;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static gr.cytech.sendium.core.storage.OutboundStorageException.Reason.INVALID_TRANSITION;
import static gr.cytech.sendium.core.storage.OutboundStorageException.Reason.UNAVAILABLE;

/** Durable selected executions; only the bounded routing window has runtime entries. */
public final class FileSelectedRouterStore implements SelectedRouterStore<StandardMessage> {
    private final FilePendingMessageStore pending;
    private final int capacity;
    private final Clock clock;
    private final MeterRegistry metrics;
    private final Map<String, Timer> timers = new ConcurrentHashMap<>();
    private final Map<SelectionId, Entry> active = new HashMap<>();
    private final ArrayDeque<Entry> ready = new ArrayDeque<>();
    private State state = State.NEW;
    private PendingSpool spool;

    public FileSelectedRouterStore(FilePendingMessageStore pending, int capacity) {
        this(pending, capacity, Clock.systemUTC());
    }

    public FileSelectedRouterStore(FilePendingMessageStore pending, int capacity, Clock clock) {
        this(pending, capacity, clock, null);
    }

    public FileSelectedRouterStore(FilePendingMessageStore pending, int capacity, Clock clock, MeterRegistry metrics) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("selectedConfiguration|error|reason:nonPositiveCapacity");
        }
        this.pending = Objects.requireNonNull(pending, "pending");
        this.capacity = capacity;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.metrics = metrics;
    }

    @Override
    public void open() {
        measured("selected_open", () -> {
            openStore();
            return null;
        });
    }

    private void openStore() {
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
        return measured("selected_select", () -> select(limit));
    }

    private int select(int limit) {
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
        long started = System.nanoTime();
        Throwable outcome = null;
        try {
            return takeSelected(timeout);
        } catch (InterruptedException | RuntimeException | Error failure) {
            outcome = failure;
            throw failure;
        } finally {
            record("selected_take", started, outcome);
        }
    }

    private Optional<Selected<StandardMessage>> takeSelected(Duration timeout) throws InterruptedException {
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
        measured("selected_release", () -> {
            releaseSelected(selected);
            return null;
        });
    }

    private void releaseSelected(Selected<StandardMessage> selected) {
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
        measured("selected_mark_routed", () -> {
            markSelectionRouted(selection);
            return null;
        });
    }

    private void markSelectionRouted(SelectionId selection) {
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
        measured("selected_complete", () -> {
            completeSelection(selection);
            return null;
        });
    }

    private void completeSelection(SelectionId selection) {
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
        measured("selected_close", () -> {
            closeStore();
            return null;
        });
    }

    private void closeStore() {
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

    private <T> T measured(String operation, Supplier<T> action) {
        long started = System.nanoTime();
        Throwable outcome = null;
        try {
            return action.get();
        } catch (RuntimeException | Error failure) {
            outcome = failure;
            throw failure;
        } finally {
            record(operation, started, outcome);
        }
    }

    private void record(String operation, long started, Throwable failure) {
        if (metrics == null) {
            return;
        }
        String outcome = failure == null ? "success" : "error";
        String reason = failure == null ? "NONE" : failure instanceof OutboundStorageException storage ? storage.reason().name() : "OTHER";
        String key = operation + '/' + outcome + '/' + reason;
        timers.computeIfAbsent(key, ignored -> Timer.builder(FilePendingMessageStore.OPERATION_METRIC)
                        .description("File selected storage operation latency including lock wait; error counts by bounded reason")
                        .tags("backend", "file", "operation", operation, "outcome", outcome, "reason", reason)
                        .register(metrics))
                .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
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
