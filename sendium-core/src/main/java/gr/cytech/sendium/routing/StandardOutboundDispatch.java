package gr.cytech.sendium.routing;

import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.outbound.OutboundCoordinator;
import gr.cytech.sendium.core.outbound.OutboundWork.Destination;
import gr.cytech.sendium.core.outbound.OutboundWork.Routed;
import gr.cytech.sendium.core.outbound.OutboundWork.Selected;
import gr.cytech.sendium.core.outbound.OutboundWork.WorkId;
import gr.cytech.sendium.core.smpp.client.CoordinatedSmppSubmission;
import gr.cytech.sendium.core.smpp.client.SmppClientWorker;
import gr.cytech.sendium.core.storage.OutboundStage;
import gr.cytech.sendium.core.storage.OutboundStorageException;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Explicitly assembled routing and provider-completion boundary for the single-destination lifecycle. */
public final class StandardOutboundDispatch implements AutoCloseable {
    private final OutboundCoordinator<StandardMessage> coordinator;
    private final StandardRoutingManager routing;
    private final ScheduledExecutorService scheduler;
    private final boolean ownsScheduler;
    private final ConcurrentMap<SmppClientWorker<StandardMessage>, Semaphore> workerSlots = new ConcurrentHashMap<>();
    private final ConcurrentMap<WorkId, CompletionStage<Void>> activeExecutions = new ConcurrentHashMap<>();
    private boolean quiescing;

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
        if (quiescing) {
            coordinator.returnToRouter(selected);
            return Optional.empty();
        }
        RoutingLookupResult result;
        try {
            result = routing.lookupForLifecycle(selected.message());
        } catch (IOException | RuntimeException failure) {
            coordinator.returnToRouter(selected);
            throw failure;
        }
        if (result.getDestinations().isEmpty()) {
            coordinator.returnToRouter(selected);
            return Optional.empty();
        }
        if (result.getDestinations().size() != 1) {
            coordinator.returnToRouter(selected);
            throw new OutboundStorageException(OutboundStage.Role.ROUTER_QUEUE,
                    OutboundStorageException.Reason.UNSUPPORTED, "Lifecycle routing requires one destination");
        }
        String destination = result.getDestinations().getFirst().getFullName();
        return Optional.of(coordinator.route(selected, new Destination<>(destination, selected.message())));
    }

    /** Takes recorded work for provider processing; taking or enqueuing it is not terminal completion. */
    public Optional<Routed<StandardMessage>> takeForProvider(String destination, Duration timeout) throws InterruptedException {
        return coordinator.takeForDestination(destination, timeout);
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
        execution.completion = track(work.id(), coordinator.complete(work.id(), attempts));
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
    public synchronized void quiesce() {
        coordinator.quiesce();
        quiescing = true;
    }

    /** Waits for terminal handoffs and cleanup. A timeout or failed handoff leaves ownership intact. */
    public boolean awaitProviderDrain(Duration timeout) throws InterruptedException {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("Negative drain timeout");
        }
        CompletableFuture<?>[] completions;
        synchronized (this) {
            if (!quiescing) {
                throw new IllegalStateException("Quiesce dispatch before awaiting provider drain");
            }
            completions = activeExecutions.values().stream().map(CompletionStage::toCompletableFuture)
                    .toArray(CompletableFuture[]::new);
        }
        try {
            CompletableFuture.allOf(completions).get(timeout.toNanos(), TimeUnit.NANOSECONDS);
            removeSuccessfulExecutions();
            return true;
        } catch (java.util.concurrent.TimeoutException timeoutFailure) {
            return false;
        } catch (java.util.concurrent.ExecutionException failure) {
            throw new IllegalStateException("Resolve failed provider handoffs or terminal cleanup before shutdown", failure.getCause());
        }
    }

    private CompletionStage<Void> track(WorkId id, CompletionStage<Void> completion) {
        activeExecutions.put(id, completion);
        completion.whenComplete((ignored, failure) -> {
            if (failure == null) {
                activeExecutions.remove(id, completion);
            }
        });
        return completion;
    }

    private void requireDispatching() {
        if (quiescing) {
            throw new IllegalStateException("Outbound dispatch is quiescing");
        }
    }

    private void removeSuccessfulExecutions() {
        activeExecutions.entrySet().removeIf(entry -> {
            var completion = entry.getValue().toCompletableFuture();
            return completion.isDone() && !completion.isCompletedExceptionally();
        });
    }

    /** Call after drain and execution-loop stop/join; parent stores remain application-owned. */
    @Override
    public synchronized void close() {
        quiesce();
        removeSuccessfulExecutions();
        if (!activeExecutions.isEmpty()) {
            throw new IllegalStateException("Drain provider executions before closing outbound dispatch");
        }
        if (ownsScheduler) {
            scheduler.shutdown();
        }
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
        return track(work.id(), coordinator.complete(work.id(), CompletableFuture.allOf(parts)));
    }

    public enum AttemptState {
        QUEUED, PREPARING, AWAITING_COMPLETION, COMPLETED, FAILED
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
            Semaphore slot = null;
            try {
                if (worker == null) {
                    worker = Objects.requireNonNull(resolveDestination.get(), "retry destination");
                }
                if (!worker.isKeepOnRunning()) {
                    throw new IllegalStateException("Destination worker stopped: " + worker.getFullName());
                }
                if (worker.isPause()) {
                    synchronized (this) {
                        state = AttemptState.QUEUED;
                        schedule(100);
                    }
                    return;
                }
                slot = workerSlots.computeIfAbsent(worker, destination ->
                        new Semaphore(Math.max(1, destination.getThreadCount())));
                if (!slot.tryAcquire()) {
                    slot = null;
                    synchronized (this) {
                        state = AttemptState.QUEUED;
                        schedule(100);
                    }
                    return;
                }
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
                synchronized (this) {
                    state = AttemptState.FAILED;
                    failure = error;
                }
            } finally {
                if (slot != null) {
                    slot.release();
                }
            }
        }
    }

    /** Runtime attempt handles remain associated with the parent's recorded work, never new admissions. */
    public static final class ProviderExecution {
        private final List<CoordinatedSmppSubmission<StandardMessage>> submissions = new CopyOnWriteArrayList<>();
        private final List<ProviderAttempt> providerAttempts = new CopyOnWriteArrayList<>();
        private CompletionStage<Void> completion;
        private CompletionStage<Void> attempts;
        private java.util.function.Supplier<CompletionStage<Void>> retryCompletion;

        public CompletionStage<Void> completion() {
            return completion;
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
            completion = retryCompletion.get();
            return completion;
        }
    }
}
