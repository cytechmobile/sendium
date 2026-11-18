package gr.cytech.sendium.core.storage.file;

import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.outbound.OutboundWork.Assignment;
import gr.cytech.sendium.core.outbound.OutboundWork.Completed;
import gr.cytech.sendium.core.outbound.OutboundWork.Routed;
import gr.cytech.sendium.core.outbound.OutboundWork.SelectionId;
import gr.cytech.sendium.core.outbound.OutboundWork.SourceId;
import gr.cytech.sendium.core.outbound.OutboundWork.WorkId;
import gr.cytech.sendium.core.storage.OutboundStorageException;
import gr.cytech.sendium.core.storage.RoutedWorkStore;
import gr.cytech.sendium.core.storage.file.RoutedRecordCodec.StoredWork;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static gr.cytech.sendium.core.storage.OutboundStorageException.Reason.CAPACITY_EXCEEDED;
import static gr.cytech.sendium.core.storage.OutboundStorageException.Reason.INVALID_TRANSITION;
import static gr.cytech.sendium.core.storage.OutboundStorageException.Reason.UNAVAILABLE;

/** One file assignment per selection. Only requested destinations acquire bounded runtime windows. */
public final class FileRoutedWorkStore implements RoutedWorkStore<StandardMessage> {
    private final FilePendingMessageStore pending;
    private final int capacity;
    private final int prefetch;
    private final MeterRegistry metrics;
    private final Map<String, Timer> timers = new ConcurrentHashMap<>();
    private final Map<WorkId, Entry> active = new HashMap<>();
    private final Map<String, ArrayDeque<Entry>> ready = new HashMap<>();
    private PendingSpool spool;
    private State state = State.NEW;

    public FileRoutedWorkStore(FilePendingMessageStore pending, int capacity) {
        this(pending, capacity, 1);
    }

    /** Prefetch bounds queued plus taken projections per requested destination, not retained disk records. */
    public FileRoutedWorkStore(FilePendingMessageStore pending, int capacity, int prefetch) {
        this(pending, capacity, prefetch, null);
    }

    public FileRoutedWorkStore(FilePendingMessageStore pending, int capacity, int prefetch, MeterRegistry metrics) {
        if (capacity <= 0 || prefetch <= 0) {
            throw new IllegalArgumentException("routedConfiguration|error|reason:nonPositiveCapacity");
        }
        this.pending = Objects.requireNonNull(pending, "pending");
        this.capacity = capacity;
        this.prefetch = prefetch;
        this.metrics = metrics;
    }

    @Override
    public void open() {
        measured("routed_open", () -> {
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
            pending.requireReady(Role.ROUTED_WORK);
            // Reuse the existing package-local accessor to the one pending-owned physical spool.
            spool = pending.selectedSpool();
            spool.attachRouted(this);
            state = State.READY;
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
    public Routed<StandardMessage> record(Assignment<StandardMessage> assignment) {
        return measured("routed_record", () -> recordAssignment(assignment));
    }

    private Routed<StandardMessage> recordAssignment(Assignment<StandardMessage> assignment) {
        Objects.requireNonNull(assignment, "assignment");
        pending.selectionLock().lock();
        try {
            requireReady();
            Optional<StoredWork> found = spool.routedForSelection(assignment.selection());
            if (found.isPresent()) {
                StoredWork existing = found.orElseThrow();
                if (!existing.sources().equals(assignment.sources()) || !existing.work().destination().equals(assignment.destination().name())) {
                    throw invalid("conflictingAssignment");
                }
                return projection(existing.work(), existing.work().message());
            }
            for (var source : assignment.sources()) {
                if (spool.routedSourceOwner(source).isPresent() || !pending.contains(source)) {
                    throw invalid("invalidSourceOwnership");
                }
            }
            if (spool.routedCount() >= capacity) {
                throw new OutboundStorageException(Role.ROUTED_WORK, CAPACITY_EXCEEDED, "routedAdmission|error|reason:capacityReached");
            }
            var work = new Routed<>(new WorkId(UUID.randomUUID()), assignment.selection(), assignment.destination().name(),
                    pending.snapshot(assignment.destination().message(), Role.ROUTED_WORK));
            spool.publishRouted(new StoredWork(work, assignment.sources()));
            pending.selectionChanged().signalAll();
            return projection(work, work.message());
        } finally {
            pending.selectionLock().unlock();
        }
    }

    @Override
    public Optional<Routed<StandardMessage>> take(String destination, Duration timeout) throws InterruptedException {
        long started = System.nanoTime();
        Throwable failure = null;
        try {
            return takeWork(destination, timeout);
        } catch (RuntimeException | InterruptedException | Error outcome) {
            failure = outcome;
            throw outcome;
        } finally {
            recordMetric("routed_take", started, failure);
        }
    }

    private Optional<Routed<StandardMessage>> takeWork(String destination, Duration timeout) throws InterruptedException {
        Objects.requireNonNull(destination, "destination");
        if (destination.isBlank()) {
            throw new IllegalArgumentException("routedTake|error|reason:blankDestination");
        }
        long remaining = timeoutNanos(timeout);
        pending.selectionLock().lockInterruptibly();
        try {
            while (true) {
                requireReady();
                refill(destination);
                ArrayDeque<Entry> queue = ready.get(destination);
                if (queue != null && !queue.isEmpty()) {
                    Entry entry = queue.peekFirst();
                    Routed<StandardMessage> projection = projection(entry.record.work(), entry.message);
                    queue.removeFirst();
                    if (queue.isEmpty()) {
                        ready.remove(destination);
                    }
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
    public void release(Routed<StandardMessage> taken) {
        measured("routed_release", () -> {
            releaseWork(taken);
            return null;
        });
    }

    private void releaseWork(Routed<StandardMessage> taken) {
        Objects.requireNonNull(taken, "taken");
        pending.selectionLock().lock();
        try {
            requireReady();
            Entry entry = active.get(taken.id());
            if (entry != null && entry.lastReturned == taken) {
                return;
            }
            if (entry == null || entry.inFlight != taken) {
                throw invalid("notCurrentTake");
            }
            entry.message = pending.snapshot(taken.message(), Role.ROUTED_WORK);
            entry.inFlight = null;
            entry.lastReturned = taken;
            ready.computeIfAbsent(taken.destination(), ignored -> new ArrayDeque<>()).addLast(entry);
            pending.selectionChanged().signalAll();
        } finally {
            pending.selectionLock().unlock();
        }
    }

    @Override
    public Completed complete(WorkId id) {
        return measured("routed_complete", () -> completeWork(id));
    }

    private Completed completeWork(WorkId id) {
        Objects.requireNonNull(id, "id");
        pending.selectionLock().lock();
        try {
            requireReady();
            var terminal = spool.routedTerminal(id);
            if (terminal.isPresent()) {
                return terminal.orElseThrow().completed();
            }
            Entry entry = active.get(id);
            if (entry == null || entry.inFlight == null) {
                throw invalid("notTaken");
            }
            Completed completed = spool.completeRouted(entry.record).completed();
            active.remove(id);
            pending.selectionChanged().signalAll();
            return completed;
        } finally {
            pending.selectionLock().unlock();
        }
    }

    /** Bounded recovery enumeration; call complete(id) to obtain the retained source-cleanup ownership. */
    public List<WorkId> terminalWorkAfter(WorkId afterExclusive, int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("routedRecovery|error|reason:nonPositivePageLimit");
        }
        pending.selectionLock().lock();
        try {
            requireReady();
            return spool.terminalRoutedAfter(afterExclusive, limit);
        } finally {
            pending.selectionLock().unlock();
        }
    }

    /** Resolve cleanup ownership only for a requested execution, without hydrating the backlog. */
    public Set<SourceId> sourcesFor(WorkId id) {
        Objects.requireNonNull(id, "id");
        pending.selectionLock().lock();
        try {
            requireReady();
            return spool.routedWork(id).sources();
        } finally {
            pending.selectionLock().unlock();
        }
    }

    @Override
    public void forget(SelectionId selection) {
        measured("routed_forget", () -> {
            forgetSelection(selection);
            return null;
        });
    }

    private void forgetSelection(SelectionId selection) {
        Objects.requireNonNull(selection, "selection");
        pending.selectionLock().lock();
        try {
            requireReady();
            Optional<StoredWork> found = spool.routedForSelection(selection);
            if (found.isEmpty()) {
                return;
            }
            StoredWork record = found.orElseThrow();
            if (spool.routedTerminal(record.work().id()).isEmpty()) {
                throw invalid("nonterminalWork");
            }
            if (record.sources().stream().anyMatch(pending::contains) || spool.findSelected(selection).isPresent()) {
                throw invalid("completeEarlierSourcesFirst");
            }
            spool.forgetRouted(record);
            pending.selectionChanged().signalAll();
        } finally {
            pending.selectionLock().unlock();
        }
    }

    private void refill(String destination) {
        long retained = active.values().stream().filter(entry -> entry.record.work().destination().equals(destination)).count();
        int space = (int) Math.max(0, prefetch - retained);
        if (space == 0) {
            return;
        }
        for (WorkId id : spool.routedCandidates(destination, space)) {
            StoredWork record = spool.routedWork(id);
            if (!record.work().destination().equals(destination)) {
                state = State.FAILED;
                throw new OutboundStorageException(Role.ROUTED_WORK, UNAVAILABLE, "routedRecovery|error|reason:destinationReferenceMismatch");
            }
            spool.unscheduleRouted(record);
            Entry entry = new Entry(record);
            active.put(id, entry);
            ready.computeIfAbsent(destination, ignored -> new ArrayDeque<>()).addLast(entry);
        }
    }

    private Routed<StandardMessage> projection(Routed<StandardMessage> work, StandardMessage message) {
        return new Routed<>(work.id(), work.selection(), work.destination(), pending.snapshot(message, Role.ROUTED_WORK));
    }

    private void requireReady() {
        if (state != State.READY) {
            throw new OutboundStorageException(Role.ROUTED_WORK, UNAVAILABLE, "routedLifecycle|error|reason:notReady");
        }
        pending.requireReady(Role.ROUTED_WORK);
    }

    private static OutboundStorageException invalid(String reason) {
        return new OutboundStorageException(Role.ROUTED_WORK, INVALID_TRANSITION, "routedTransition|error|reason:" + reason);
    }

    private static long timeoutNanos(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("routedTake|error|reason:negativeTimeout");
        }
        try {
            return timeout.toNanos();
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
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

    private <T> T measured(String operation, Supplier<T> action) {
        long started = System.nanoTime();
        Throwable failure = null;
        try {
            return action.get();
        } catch (RuntimeException | Error outcome) {
            failure = outcome;
            throw outcome;
        } finally {
            recordMetric(operation, started, failure);
        }
    }

    private void recordMetric(String operation, long started, Throwable failure) {
        if (metrics == null) {
            return;
        }
        String outcome = failure == null ? "success" : "error";
        String reason = failure == null ? "NONE" : failure instanceof OutboundStorageException storage ? storage.reason().name() : "OTHER";
        String key = operation + '/' + outcome + '/' + reason;
        timers.computeIfAbsent(key, ignored -> Timer.builder(FilePendingMessageStore.OPERATION_METRIC)
                        .description("File routed storage operation latency including lock wait; error counts by bounded reason")
                        .tags("backend", "file", "operation", operation, "outcome", outcome, "reason", reason)
                        .register(metrics))
                .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
    }

    private static final class Entry {
        private final StoredWork record;
        private StandardMessage message;
        private Routed<StandardMessage> inFlight;
        private Routed<StandardMessage> lastReturned;

        private Entry(StoredWork record) {
            this.record = record;
            this.message = record.work().message();
        }
    }
}
