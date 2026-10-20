package gr.cytech.sendium.routing;

import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.outbound.OutboundCoordinator;
import gr.cytech.sendium.core.outbound.OutboundWork.Destination;
import gr.cytech.sendium.core.outbound.OutboundWork.Routed;
import gr.cytech.sendium.core.outbound.OutboundWork.Selected;
import gr.cytech.sendium.core.outbound.OutboundWork.SelectionId;
import gr.cytech.sendium.core.outbound.OutboundWork.WorkId;
import gr.cytech.sendium.core.smpp.client.CoordinatedSmppSubmission;
import gr.cytech.sendium.core.smpp.client.SmppClientWorker;
import gr.cytech.sendium.core.storage.OutboundStage;
import gr.cytech.sendium.core.storage.OutboundStorageException;
import gr.cytech.sendium.external.filter.FilterException;
import gr.cytech.sendium.external.filter.FilterStatusCodes;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Explicitly assembled routing and provider-completion boundary for the single-destination lifecycle. */
public final class StandardOutboundDispatch implements AutoCloseable {
    private final OutboundCoordinator<StandardMessage> coordinator;
    private final StandardRoutingManager routing;
    private final ScheduledExecutorService scheduler;
    private final boolean ownsScheduler;
    private final ConcurrentMap<SmppClientWorker<StandardMessage>, WorkerExecution> workerExecutors = new ConcurrentHashMap<>();
    private final ConcurrentMap<WorkId, CompletableFuture<Void>> activeExecutions = new ConcurrentHashMap<>();
    private final Object completionLock = new Object();
    private boolean shuttingDown;
    private boolean closed;
    private final ConcurrentMap<SelectionId, PendingRouting> routingTransitions = new ConcurrentHashMap<>();

    public StandardOutboundDispatch(OutboundCoordinator<StandardMessage> coordinator, StandardRoutingManager routing) {
        this(coordinator, routing, Executors.newScheduledThreadPool(2, task -> {
            var thread = new Thread(task, "outbound-provider-attempt");
            thread.setDaemon(true);
            return thread;
        }), true);
    }

    /** Applications may supply their execution scheduler without transferring its lifecycle ownership. */
    public StandardOutboundDispatch(OutboundCoordinator<StandardMessage> coordinator, StandardRoutingManager routing,
                                    ScheduledExecutorService scheduler) {
        this(coordinator, routing, scheduler, false);
    }

    private StandardOutboundDispatch(OutboundCoordinator<StandardMessage> coordinator, StandardRoutingManager routing,
                                     ScheduledExecutorService scheduler, boolean ownsScheduler) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.routing = Objects.requireNonNull(routing, "routing");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.ownsScheduler = ownsScheduler;
    }

    /**
     * Routes one owned selection. A miss or lookup failure returns it to routing. If recording the
     * destination fails, the caller retains the selection handle to retry the same routing intent.
     */
    public synchronized Optional<Routed<StandardMessage>> routeSelected(Selected<StandardMessage> selected) throws IOException {
        Objects.requireNonNull(selected, "selected");
        var pending = routingTransitions.get(selected.id());
        if (pending != null) {
            if (pending.selected() != selected) {
                throw new IllegalArgumentException("Routing retry requires its original selection handle");
            }
            return Optional.of(recordRouting(pending));
        }
        if (shuttingDown) {
            coordinator.requeueForRouting(selected);
            return Optional.empty();
        }
        RoutingLookupResult result;
        try {
            result = routing.lookupForLifecycle(selected.message());
        } catch (FilterException failure) {
            if (failure.getStatusCode() == FilterStatusCodes.DROP) {
                coordinator.discard(selected.id(), CompletableFuture.completedStage(null)).toCompletableFuture().join();
                return Optional.empty();
            }
            if (failure.getStatusCode() == FilterStatusCodes.RETRY) {
                selected.message().rtxCnt++;
            }
            coordinator.requeueForRouting(selected);
            throw failure;
        } catch (IOException | RuntimeException failure) {
            coordinator.requeueForRouting(selected);
            throw failure;
        }
        if (result.getDestinations().isEmpty()) {
            coordinator.requeueForRouting(selected);
            return Optional.empty();
        }
        if (result.getDestinations().size() != 1) {
            coordinator.requeueForRouting(selected);
            throw new OutboundStorageException(OutboundStage.Role.ROUTER_QUEUE,
                    OutboundStorageException.Reason.UNSUPPORTED, "Lifecycle routing requires one destination");
        }
        String destination = result.getDestinations().getFirst().getFullName();
        var transition = new PendingRouting(selected, new Destination<>(destination, selected.message()));
        routingTransitions.putIfAbsent(selected.id(), transition);
        var recorded = recordRouting(routingTransitions.get(selected.id()));
        return Optional.of(recorded);
    }

    /** Retries recording the original routing intent without rerunning lookup or filters. */
    public synchronized void retryRoutingTransitions() {
        retireStoppedWorkers();
        RuntimeException failure = null;
        for (var transition : List.copyOf(routingTransitions.values())) {
            try {
                recordRouting(transition);
            } catch (RuntimeException error) {
                if (failure == null) {
                    failure = error;
                } else if (failure != error) {
                    failure.addSuppressed(error);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private Routed<StandardMessage> recordRouting(PendingRouting transition) {
        var recorded = coordinator.recordToRouted(transition.selected(), transition.destination());
        routingTransitions.remove(transition.selected().id(), transition);
        return recorded;
    }

    /** Takes recorded work for provider processing; taking or enqueuing it is not terminal completion. */
    public Optional<Routed<StandardMessage>> takeForProvider(String destination, Duration timeout) throws InterruptedException {
        return coordinator.takeFromRouted(destination, timeout);
    }

    /** Runs an explicitly taken SMPP work item and carries its ownership through replacement attempts. */
    public synchronized ProviderExecution submitToProvider(Routed<StandardMessage> work, SmppClientWorker<StandardMessage> worker)
            throws Exception {
        requireDispatching();
        if (!work.destination().equals(worker.getFullName())) {
            throw new IllegalArgumentException("Provider does not match the recorded destination");
        }
        var execution = new ProviderExecution();
        execution.retryCompletion = () -> track(work.id(), coordinator.complete(work.id(), execution.attempts));
        CompletionStage<Void> attempts = scheduleAttempt(work.message(), () -> worker, execution);
        execution.attempts = attempts;
        execution.setCompletion(track(work.id(), coordinator.complete(work.id(), attempts)));
        return execution;
    }

    private CompletionStage<Void> scheduleAttempt(StandardMessage message,
                                                   Supplier<SmppClientWorker<StandardMessage>> destination,
                                                   ProviderExecution execution) {
        var attempt = new ProviderAttempt(message, destination, execution);
        execution.providerAttempts.add(attempt);
        attempt.schedule(0);
        return attempt.completed.minimalCompletionStage();
    }

    private SmppClientWorker<StandardMessage> retryDestination(StandardMessage message) {
        try {
            RoutingLookupResult result = routing.lookupForLifecycle(message);
            if (result.getDestinations().size() != 1 ||
                    !(result.getDestinations().getFirst() instanceof SmppClientWorker<?>)) {
                throw new OutboundStorageException(OutboundStage.Role.ROUTER_QUEUE,
                        OutboundStorageException.Reason.UNAVAILABLE, "No single SMPP destination for retry");
            }
            return (SmppClientWorker<StandardMessage>) result.getDestinations().getFirst();
        } catch (IOException failure) {
            throw new java.util.concurrent.CompletionException(failure);
        }
    }

    /** Stops new admission/takes and dispatch; existing provider retries and callbacks remain enabled. */
    public synchronized void beginShutdown() {
        coordinator.beginShutdown();
        shuttingDown = true;
    }

    /** Waits for terminal handoffs and cleanup. A timeout or failed handoff leaves ownership intact. */
    public boolean awaitProviderDrain(Duration timeout) throws InterruptedException {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("Negative drain timeout");
        }
        long remaining = timeout.toNanos();
        synchronized (this) {
            if (!shuttingDown) {
                throw new IllegalStateException("Begin dispatch shutdown before awaiting provider drain");
            }
        }
        synchronized (completionLock) {
            while (true) {
                removeSuccessfulExecutions();
                for (var completion : activeExecutions.values()) {
                    if (completion.isCompletedExceptionally()) {
                        try {
                            completion.join();
                        } catch (java.util.concurrent.CompletionException | java.util.concurrent.CancellationException failure) {
                            throw new IllegalStateException("Resolve failed provider handoffs or terminal cleanup before shutdown",
                                    failure.getCause() == null ? failure : failure.getCause());
                        }
                    }
                }
                if (activeExecutions.isEmpty()) {
                    return true;
                }
                if (remaining <= 0) {
                    return false;
                }
                long started = System.nanoTime();
                TimeUnit.NANOSECONDS.timedWait(completionLock, remaining);
                remaining -= System.nanoTime() - started;
            }
        }
    }

    private CompletableFuture<Void> track(WorkId id, CompletionStage<Void> completion) {
        var monitor = completion.toCompletableFuture();
        synchronized (completionLock) {
            activeExecutions.put(id, monitor);
            completionLock.notifyAll();
        }
        monitor.whenComplete((ignored, failure) -> {
            synchronized (completionLock) {
                if (failure == null) {
                    activeExecutions.remove(id, monitor);
                }
                completionLock.notifyAll();
            }
        });
        return monitor;
    }

    private void requireDispatching() {
        if (shuttingDown) {
            throw new IllegalStateException("Outbound dispatch is shutting down");
        }
    }

    private void removeSuccessfulExecutions() {
        synchronized (completionLock) {
            activeExecutions.values().removeIf(completion -> completion.isDone() && !completion.isCompletedExceptionally());
        }
    }

    /** Call after drain and execution-loop stop/join; parent stores remain application-owned. */
    @Override
    public synchronized void close() {
        beginShutdown();
        removeSuccessfulExecutions();
        if (!activeExecutions.isEmpty()) {
            throw new IllegalStateException("Drain provider executions before closing outbound dispatch");
        }
        if (!routingTransitions.isEmpty()) {
            throw new IllegalStateException("Resolve unfinished routing transitions before closing outbound dispatch");
        }
        closed = true;
        workerExecutors.values().forEach(WorkerExecution::stop);
        workerExecutors.clear();
        if (ownsScheduler) {
            scheduler.shutdown();
        }
    }

    private synchronized WorkerExecution executionFor(SmppClientWorker<StandardMessage> worker) {
        if (closed) {
            throw new IllegalStateException("Outbound dispatch is closed");
        }
        return workerExecutors.computeIfAbsent(worker, WorkerExecution::new);
    }

    private void retireStoppedWorkers() {
        workerExecutors.entrySet().removeIf(entry -> {
            if (entry.getKey().isKeepOnRunning()) {
                return false;
            }
            entry.getValue().stop();
            return true;
        });
    }

    /** Completes explicitly supplied provider outcomes once all required part handoffs succeed. */
    public synchronized CompletionStage<Void> finishProviderParts(Routed<StandardMessage> work,
                                                       List<? extends CompletionStage<Void>> requiredHandoffs) {
        Objects.requireNonNull(work, "work");
        List<? extends CompletionStage<Void>> handoffs = List.copyOf(requiredHandoffs);
        if (handoffs.isEmpty()) {
            throw new IllegalArgumentException("A provider outcome must include at least one part");
        }
        CompletableFuture<?>[] parts = handoffs.stream().map(CompletionStage::toCompletableFuture)
                .toArray(CompletableFuture[]::new);
        return track(work.id(), coordinator.complete(work.id(), CompletableFuture.allOf(parts))).minimalCompletionStage();
    }

    /** Owned attempts wait once in FIFO order; preparation threads continue directly when capacity frees. */
    private final class WorkerExecution {
        private final SmppClientWorker<StandardMessage> worker;
        private final int threads;
        private final ArrayDeque<ProviderAttempt> waiting = new ArrayDeque<>();
        private final ThreadPoolExecutor executor;
        private int running;
        private ScheduledFuture<?> wakeup;

        private WorkerExecution(SmppClientWorker<StandardMessage> worker) {
            this.worker = worker;
            threads = Math.max(1, worker.getThreadCount());
            executor = new ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(threads),
                    Thread.ofPlatform().name("outbound-provider-" + worker.getFullName() + "-", 1).daemon(true).factory());
            executor.allowCoreThreadTimeOut(true);
        }

        private synchronized void enqueue(ProviderAttempt attempt) {
            waiting.addLast(attempt);
            dispatchWaiting();
        }

        private void dispatchWaiting() {
            if (waiting.isEmpty()) {
                return;
            }
            try {
                if (!worker.isKeepOnRunning() || executor.isShutdown()) {
                    throw new IllegalStateException("Destination worker stopped: " + worker.getFullName());
                }
                if (worker.isPause()) {
                    if (wakeup == null) {
                        wakeup = scheduler.schedule(this::resume, 100, TimeUnit.MILLISECONDS);
                    }
                    return;
                }
                int available = Math.min(threads - running, waiting.size());
                for (int i = 0; i < available; i++) {
                    running++;
                    try {
                        executor.execute(this::drain);
                    } catch (RuntimeException failure) {
                        running--;
                        throw failure;
                    }
                }
            } catch (RuntimeException failure) {
                failWaiting(failure);
            }
        }

        private synchronized void resume() {
            wakeup = null;
            dispatchWaiting();
        }

        private void drain() {
            try {
                while (true) {
                    ProviderAttempt next;
                    synchronized (this) {
                        if (waiting.isEmpty() || !worker.isKeepOnRunning() || worker.isPause()) {
                            return;
                        }
                        next = waiting.removeFirst();
                    }
                    next.prepare();
                }
            } finally {
                synchronized (this) {
                    running--;
                    dispatchWaiting();
                }
            }
        }

        private void failWaiting(RuntimeException failure) {
            while (!waiting.isEmpty()) {
                waiting.removeFirst().fail(failure);
            }
        }

        private synchronized void stop() {
            if (wakeup != null) {
                wakeup.cancel(false);
                wakeup = null;
            }
            failWaiting(new IllegalStateException("Destination worker stopped: " + worker.getFullName()));
            executor.shutdown();
        }
    }

    public enum AttemptState {
        QUEUED, PREPARING, AWAITING_COMPLETION, COMPLETED, FAILED
    }

    private record PendingRouting(Selected<StandardMessage> selected, Destination<StandardMessage> destination) {
    }

    public record AttemptSnapshot(String destination, AttemptState state, Throwable failure) {
    }

    private final class ProviderAttempt implements Runnable {
        private final StandardMessage message;
        private final Supplier<SmppClientWorker<StandardMessage>> resolveDestination;
        private final ProviderExecution execution;
        private final CompletableFuture<Void> completed = new CompletableFuture<>();
        private volatile SmppClientWorker<StandardMessage> worker;
        private AttemptState state = AttemptState.QUEUED;
        private Throwable failure;

        private ProviderAttempt(StandardMessage message, Supplier<SmppClientWorker<StandardMessage>> destination,
                                 ProviderExecution execution) {
            this.message = message;
            this.resolveDestination = destination;
            this.execution = execution;
        }

        private synchronized AttemptSnapshot snapshot() {
            return new AttemptSnapshot(worker == null ? null : worker.getFullName(), state, failure);
        }

        private synchronized void schedule(long delay) {
            try {
                scheduler.schedule(this, delay, TimeUnit.MILLISECONDS);
            } catch (RuntimeException error) {
                state = AttemptState.FAILED;
                failure = error;
            }
        }

        private synchronized void retry() {
            if (state == AttemptState.FAILED) {
                state = AttemptState.QUEUED;
                failure = null;
                schedule(0);
            }
        }

        @Override
        public void run() {
            synchronized (this) {
                if (state != AttemptState.QUEUED) {
                    return;
                }
                state = AttemptState.PREPARING;
            }
            try {
                if (worker == null) {
                    worker = Objects.requireNonNull(resolveDestination.get(), "retry destination");
                }
                if (!worker.isKeepOnRunning()) {
                    throw new IllegalStateException("Destination worker stopped: " + worker.getFullName());
                }
                synchronized (this) {
                    state = AttemptState.QUEUED;
                }
                executionFor(worker).enqueue(this);
            } catch (Exception error) {
                fail(error);
            }
        }

        private synchronized void fail(Throwable error) {
            state = AttemptState.FAILED;
            failure = error;
        }

        private void prepare() {
            synchronized (this) {
                state = AttemptState.PREPARING;
            }
            try {
                var submission = worker.submitPreparedCoordinated(message, (payload, policy) ->
                        scheduleAttempt(payload, policy == SmppClientWorker.NackHandlePolicy.RETRY_ROUTER ?
                                () -> retryDestination(payload) : () -> worker, execution));
                execution.submissions.add(submission);
                synchronized (this) {
                    state = AttemptState.AWAITING_COMPLETION;
                }
                submission.completion().whenComplete((ignored, error) -> {
                    synchronized (this) {
                        state = error == null ? AttemptState.COMPLETED : AttemptState.FAILED;
                        failure = error;
                    }
                    if (error == null) {
                        completed.complete(null);
                    }
                });
            } catch (Exception error) {
                fail(error);
            } catch (Error error) {
                fail(error);
                throw error;
            }
        }
    }

    /** Runtime attempt handles remain associated with the parent's recorded work, never new admissions. */
    public static final class ProviderExecution {
        private final List<CoordinatedSmppSubmission<StandardMessage>> submissions = new CopyOnWriteArrayList<>();
        private final List<ProviderAttempt> providerAttempts = new CopyOnWriteArrayList<>();
        private CompletionStage<Void> completion;
        private CompletableFuture<Void> completionMonitor;
        private CompletionStage<Void> attempts;
        private Supplier<CompletableFuture<Void>> retryCompletion;

        private void setCompletion(CompletableFuture<Void> monitor) {
            completionMonitor = monitor;
            completion = monitor.minimalCompletionStage();
        }

        public CompletionStage<Void> completion() {
            return completion;
        }

        /** Status checks reuse the monitor instead of copying an incomplete minimal stage on every poll. */
        public boolean isCompletedSuccessfully() {
            return completionMonitor.isDone() && !completionMonitor.isCompletedExceptionally();
        }

        public boolean hasCompletionFailure() {
            return completionMonitor.isCompletedExceptionally();
        }

        public void retryPendingHandoffs() {
            List.copyOf(submissions).forEach(CoordinatedSmppSubmission::retryPendingHandoffs);
            List.copyOf(providerAttempts).forEach(ProviderAttempt::retry);
        }

        /** Ordered runtime attempts; the parent's recorded destination remains its initial assignment. */
        public List<AttemptSnapshot> attempts() {
            return providerAttempts.stream().map(ProviderAttempt::snapshot).toList();
        }

        /** Retries store cleanup after successful handoffs without sending the message again. */
        public CompletionStage<Void> retryTerminalCleanup() {
            setCompletion(retryCompletion.get());
            return completion;
        }
    }
}
