package gr.cytech.sendium.core.outbound;

import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.outbound.OutboundWork.Assignment;
import gr.cytech.sendium.core.outbound.OutboundWork.Completed;
import gr.cytech.sendium.core.outbound.OutboundWork.Destination;
import gr.cytech.sendium.core.outbound.OutboundWork.Routed;
import gr.cytech.sendium.core.outbound.OutboundWork.Selected;
import gr.cytech.sendium.core.outbound.OutboundWork.SelectionId;
import gr.cytech.sendium.core.outbound.OutboundWork.SourceId;
import gr.cytech.sendium.core.outbound.OutboundWork.WorkId;
import gr.cytech.sendium.core.storage.OutboundStage;
import gr.cytech.sendium.core.storage.OutboundStage.Role;
import gr.cytech.sendium.core.storage.OutboundStorageException;
import gr.cytech.sendium.core.storage.PendingMessageStore;
import gr.cytech.sendium.core.storage.RoutedWorkStore;
import gr.cytech.sendium.core.storage.SelectedRouterStore;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import static gr.cytech.sendium.core.storage.OutboundStorageException.Reason.INVALID_TRANSITION;
import static gr.cytech.sendium.core.storage.OutboundStorageException.Reason.UNAVAILABLE;
import static gr.cytech.sendium.core.storage.OutboundStorageException.Reason.UNSUPPORTED;

/**
 * Coordinates fresh non-durable stages. All execution goes through this instance; its stores are not
 * independently scheduled. Durable startup reconciliation is deliberately unavailable in this baseline.
 * Handoff waits and callbacks on returned completion stages never run under the coordinator lock.
 */
public final class DefaultOutboundCoordinator<M extends StandardMessage> implements OutboundCoordinator<M> {
    private final PendingMessageStore<M> pending;
    private final SelectedRouterStore<M> router;
    private final RoutedWorkStore<M> routed;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition changed = lock.newCondition();
    private final Map<SelectionId, Context<M>> selections = new HashMap<>();
    private final Map<SourceId, Context<M>> sourceSelections = new HashMap<>();
    private final Map<WorkId, Execution<M>> executions = new HashMap<>();
    private final Set<Execution<M>> pendingReturns = new HashSet<>();
    private Lifecycle lifecycle = Lifecycle.NEW;

    public DefaultOutboundCoordinator(PendingMessageStore<M> pending, SelectedRouterStore<M> router, RoutedWorkStore<M> routed) {
        this.pending = Objects.requireNonNull(pending, "pending");
        this.router = Objects.requireNonNull(router, "router");
        this.routed = Objects.requireNonNull(routed, "routed");
    }

    @Override
    public void start() {
        lock.lock();
        try {
            if (lifecycle == Lifecycle.RUNNING) {
                return;
            }
            if (lifecycle != Lifecycle.NEW) {
                throw invalid(Role.PENDING, "Coordinator cannot restart");
            }
            List<OutboundStage> attempted = new ArrayList<>();
            try {
                open(pending, Role.PENDING, attempted);
                open(router, Role.ROUTER_QUEUE, attempted);
                open(routed, Role.ROUTED_WORK, attempted);
                lifecycle = Lifecycle.RUNNING;
            } catch (RuntimeException | Error failure) {
                for (OutboundStage stage : attempted.reversed()) {
                    try {
                        stage.close();
                    } catch (RuntimeException | Error cleanupFailure) {
                        if (cleanupFailure != failure) {
                            failure.addSuppressed(cleanupFailure);
                        }
                    }
                }
                lifecycle = Lifecycle.CLOSED;
                throw failure;
            }
        } finally {
            lock.unlock();
        }
    }

    private void open(OutboundStage stage, Role role, List<OutboundStage> attempted) {
        OutboundStage.Status status = stage.status();
        if (status.durable()) {
            throw new OutboundStorageException(role, UNSUPPORTED, "Durable stage recovery is not implemented by this coordinator");
        }
        if (status.state() != OutboundStage.State.NEW) {
            throw invalid(role, "Coordinator requires fresh, unopened stages");
        }
        attempted.add(stage);
        stage.open();
        if (stage.status().state() != OutboundStage.State.READY) {
            throw unavailable(role, "Stage did not become ready during startup");
        }
    }

    @Override
    public void quiesce() {
        lock.lock();
        try {
            if (lifecycle == Lifecycle.RUNNING) {
                lifecycle = Lifecycle.QUIESCING;
                changed.signalAll();
            } else if (lifecycle == Lifecycle.NEW) {
                throw unavailable(Role.PENDING, "Coordinator has not started");
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void admit(SourceId source, M message) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(message, "message");
        lock.lock();
        try {
            requireRunning(Role.PENDING);
            if (sourceSelections.containsKey(source)) {
                return;
            }
            pending.admit(source, message);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public int selectAndStage(int limit) {
        lock.lock();
        try {
            requireRunning(Role.ROUTER_QUEUE);
            return router.selectAndStage(limit);
        } finally {
            changed.signalAll();
            lock.unlock();
        }
    }

    @Override
    public Optional<Selected<M>> takeForRouting(Duration timeout) throws InterruptedException {
        long remaining = timeoutNanos(timeout);
        lock.lockInterruptibly();
        try {
            while (true) {
                requireRunning(Role.ROUTER_QUEUE);
                Optional<Selected<M>> next = router.take(Duration.ZERO);
                if (next.isPresent()) {
                    Selected<M> selected = next.orElseThrow();
                    Context<M> context = selections.get(selected.id());
                    if (context == null) {
                        if (selected.sources().stream().anyMatch(sourceSelections::containsKey)) {
                            router.release(selected);
                            throw invalid(Role.ROUTER_QUEUE, "Source is already owned by another selection");
                        }
                        context = new Context<>(selected);
                        selections.put(context.id, context);
                        for (SourceId source : context.sources) {
                            sourceSelections.put(source, context);
                        }
                    }
                    if (context.routingTaken || context.assignment != null || context.finishing ||
                            !context.sources.equals(selected.sources())) {
                        router.release(selected);
                        throw invalid(Role.ROUTER_QUEUE, "Selected store returned work already owned by another lifecycle step");
                    }
                    context.routingTake = selected;
                    context.routingTaken = true;
                    return next;
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
    public void returnToRouter(Selected<M> selected) {
        Objects.requireNonNull(selected, "selected");
        lock.lock();
        try {
            requireActive(Role.ROUTER_QUEUE);
            Context<M> context = context(selected.id());
            if (context.assignment != null || context.finishing) {
                throw invalid(Role.ROUTER_QUEUE, "Work transitioning to a destination or completion cannot be returned to routing");
            }
            if (context.lastRoutingReturn == selected) {
                return;
            }
            if (!context.routingTaken || context.routingTake != selected) {
                throw invalid(Role.ROUTER_QUEUE, "Only the current routing take can be returned");
            }
            router.release(selected);
            context.routingTaken = false;
            context.lastRoutingReturn = selected;
            changed.signalAll();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Routed<M> route(Selected<M> selected, Destination<M> destination) {
        Objects.requireNonNull(selected, "selected");
        Objects.requireNonNull(destination, "destination");
        lock.lock();
        try {
            requireActive(Role.ROUTER_QUEUE);
            Context<M> context = context(selected.id());
            if (context.routingTake != selected || context.finishing ||
                    (!context.routingTaken && context.assignment == null)) {
                throw invalid(Role.ROUTER_QUEUE, "Routing requires the current take owned by this coordinator");
            }
            if (context.assignment == null) {
                context.assignment = new Assignment<>(context.id, context.sources, destination);
            } else if (!context.assignment.destination().name().equals(destination.name())) {
                throw invalid(Role.ROUTED_WORK, "A routing retry cannot change the recorded destination intent");
            }
            final Routed<M> result = routed.record(context.assignment);
            registerAssignment(context, result);
            if (!context.markedRouted) {
                router.markRouted(context.id);
                context.markedRouted = true;
                context.routingTaken = false;
            }
            return result;
        } finally {
            changed.signalAll();
            lock.unlock();
        }
    }

    @Override
    public Optional<Routed<M>> takeForDestination(String destination, Duration timeout) throws InterruptedException {
        Objects.requireNonNull(destination, "destination");
        if (destination.isBlank()) {
            throw new IllegalArgumentException("Destination must not be blank");
        }
        long remaining = timeoutNanos(timeout);
        lock.lockInterruptibly();
        try {
            while (true) {
                requireRunning(Role.ROUTED_WORK);
                var returns = pendingReturns.iterator();
                while (returns.hasNext()) {
                    Execution<M> execution = returns.next();
                    routed.release(execution.take);
                    execution.take = null;
                    returns.remove();
                }
                Optional<Routed<M>> next = routed.take(destination, Duration.ZERO);
                if (next.isPresent()) {
                    Routed<M> taken = next.orElseThrow();
                    Execution<M> execution;
                    try {
                        execution = registerTaken(taken);
                    } catch (RuntimeException failure) {
                        try {
                            routed.release(taken);
                        } catch (RuntimeException releaseFailure) {
                            if (releaseFailure != failure) {
                                failure.addSuppressed(releaseFailure);
                            }
                        }
                        throw failure;
                    }
                    execution.take = taken;
                    if (!execution.context.markedRouted) {
                        pendingReturns.add(execution);
                        routed.release(taken);
                        execution.take = null;
                        pendingReturns.remove(execution);
                        throw unavailable(Role.ROUTER_QUEUE, "Retry the unfinished routing transition before dispatch");
                    }
                    execution.exposed = true;
                    return next;
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
    public void returnToDestination(Routed<M> taken) {
        Objects.requireNonNull(taken, "taken");
        lock.lock();
        try {
            requireActive(Role.ROUTED_WORK);
            Execution<M> execution = execution(taken.id());
            if (execution.context.finishing) {
                throw invalid(Role.ROUTED_WORK, "Terminal work cannot be redispatched");
            }
            if (execution.lastReturn == taken) {
                return;
            }
            if (execution.take != taken) {
                throw invalid(Role.ROUTED_WORK, "Only the current destination take can be returned");
            }
            routed.release(taken);
            execution.take = null;
            execution.exposed = false;
            execution.lastReturn = taken;
            changed.signalAll();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public CompletionStage<Void> complete(WorkId id, CompletionStage<Void> requiredHandoff) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(requiredHandoff, "requiredHandoff");
        Context<M> context;
        Attempt attempt;
        lock.lock();
        try {
            requireActive(Role.ROUTED_WORK);
            Execution<M> execution = executions.get(id);
            if (execution == null) {
                return CompletableFuture.completedStage(null);
            }
            context = execution.context;
            if (!context.finishing && (execution.take == null || !execution.exposed)) {
                throw invalid(Role.ROUTED_WORK, "Completion requires a destination take");
            }
            if (context.attempt != null) {
                return context.attempt.view;
            }
            context.finishing = true;
            attempt = new Attempt();
            context.attempt = attempt;
        } finally {
            lock.unlock();
        }
        awaitHandoff(context, attempt, requiredHandoff);
        return attempt.view;
    }

    @Override
    public CompletionStage<Void> discard(SelectionId id, CompletionStage<Void> requiredHandoff) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(requiredHandoff, "requiredHandoff");
        Context<M> context;
        Attempt attempt;
        lock.lock();
        try {
            requireActive(Role.ROUTER_QUEUE);
            context = selections.get(id);
            if (context == null) {
                return CompletableFuture.completedStage(null);
            }
            if (context.assignment != null || !context.routingTaken) {
                throw invalid(Role.ROUTER_QUEUE, "Only taken, unassigned routing work can be discarded");
            }
            if (context.attempt != null) {
                return context.attempt.view;
            }
            context.finishing = true;
            attempt = new Attempt();
            context.attempt = attempt;
        } finally {
            lock.unlock();
        }
        awaitHandoff(context, attempt, requiredHandoff);
        return attempt.view;
    }

    private void awaitHandoff(Context<M> context, Attempt attempt, CompletionStage<Void> handoff) {
        if (context.handoffAccepted) {
            finish(context, attempt, null);
        } else {
            try {
                handoff.whenComplete((ignored, failure) -> finish(context, attempt, failure));
            } catch (RuntimeException | Error failure) {
                finish(context, attempt, failure);
            }
        }
    }

    private void finish(Context<M> context, Attempt attempt, Throwable failure) {
        Throwable outcome = failure;
        lock.lock();
        try {
            if (context.attempt != attempt) {
                return;
            }
            if (outcome == null) {
                context.handoffAccepted = true;
                try {
                    cleanup(context);
                } catch (RuntimeException | Error cleanupFailure) {
                    outcome = cleanupFailure;
                }
            }
            context.attempt = null;
        } finally {
            lock.unlock();
        }
        if (outcome == null) {
            attempt.result.complete(null);
        } else {
            attempt.result.completeExceptionally(outcome);
        }
    }

    private void cleanup(Context<M> context) {
        if (context.workId != null && !context.destinationCompleted) {
            Completed completed = routed.complete(context.workId);
            if (!completed.selection().equals(context.id) || !completed.sources().equals(context.sources)) {
                throw invalid(Role.ROUTED_WORK, "Completion does not match owned source identities");
            }
            context.destinationCompleted = true;
            executions.get(context.workId).take = null;
        }
        if (!context.pendingCompleted) {
            pending.complete(context.sources);
            context.pendingCompleted = true;
        }
        if (!context.selectedCompleted) {
            router.complete(context.id);
            context.selectedCompleted = true;
        }
        if (context.workId != null) {
            routed.forget(context.id);
            pendingReturns.remove(executions.remove(context.workId));
        }
        context.sources.forEach(sourceSelections::remove);
        selections.remove(context.id);
    }

    @Override
    public void close() {
        lock.lock();
        try {
            if (lifecycle == Lifecycle.CLOSED) {
                return;
            }
            if (lifecycle == Lifecycle.NEW) {
                lifecycle = Lifecycle.CLOSED;
                return;
            }
            lifecycle = Lifecycle.QUIESCING;
            changed.signalAll();
            for (Context<M> context : selections.values()) {
                if (context.attempt != null || (context.finishing && context.handoffAccepted)) {
                    throw unavailable(Role.ROUTED_WORK, "Resolve outstanding handoffs and retry terminal cleanup before close");
                }
                if (context.assignment != null && !context.markedRouted) {
                    throw unavailable(Role.ROUTER_QUEUE, "Retry unfinished routing transitions before close");
                }
            }
            for (Context<M> context : selections.values()) {
                if (context.routingTaken) {
                    router.release(context.routingTake);
                    context.routingTaken = false;
                }
            }
            for (Execution<M> execution : executions.values()) {
                if (execution.take != null) {
                    routed.release(execution.take);
                    execution.take = null;
                }
            }
            Throwable failure = null;
            for (OutboundStage stage : List.of(routed, router, pending)) {
                try {
                    stage.close();
                } catch (RuntimeException | Error closeFailure) {
                    if (failure == null) {
                        failure = closeFailure;
                    } else if (failure != closeFailure) {
                        failure.addSuppressed(closeFailure);
                    }
                }
            }
            lifecycle = Lifecycle.CLOSED;
            selections.clear();
            sourceSelections.clear();
            executions.clear();
            pendingReturns.clear();
            if (failure instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            if (failure instanceof Error error) {
                throw error;
            }
        } finally {
            lock.unlock();
        }
    }

    private Execution<M> registerAssignment(Context<M> context, Routed<M> result) {
        if (!result.selection().equals(context.id) || !result.destination().equals(context.assignment.destination().name()) ||
                (context.workId != null && !context.workId.equals(result.id()))) {
            throw invalid(Role.ROUTED_WORK, "Recorded work does not match the routing intent");
        }
        Execution<M> existing = executions.get(result.id());
        if (existing != null) {
            if (existing.context != context || !existing.destination.equals(result.destination())) {
                throw invalid(Role.ROUTED_WORK, "Work identity is already owned by another assignment");
            }
            return existing;
        }
        Execution<M> execution = new Execution<>(context, result.destination());
        executions.put(result.id(), execution);
        context.workId = result.id();
        return execution;
    }

    private Execution<M> registerTaken(Routed<M> taken) {
        Execution<M> execution = executions.get(taken.id());
        if (execution == null) {
            Context<M> context = context(taken.selection());
            if (context.assignment == null || context.workId != null) {
                throw invalid(Role.ROUTED_WORK, "No routing intent owns this work");
            }
            execution = registerAssignment(context, taken);
        }
        if (!execution.context.id.equals(taken.selection()) || !execution.destination.equals(taken.destination()) ||
                execution.take != null || execution.context.finishing) {
            throw invalid(Role.ROUTED_WORK, "Destination store returned work already owned by another lifecycle step");
        }
        return execution;
    }

    private Context<M> context(SelectionId id) {
        Context<M> context = selections.get(Objects.requireNonNull(id, "selection"));
        if (context == null) {
            throw invalid(Role.ROUTER_QUEUE, "Selection is not owned by this coordinator");
        }
        return context;
    }

    private Execution<M> execution(WorkId id) {
        Execution<M> execution = executions.get(Objects.requireNonNull(id, "work"));
        if (execution == null) {
            throw invalid(Role.ROUTED_WORK, "Work is not owned by this coordinator");
        }
        return execution;
    }

    private void requireRunning(Role role) {
        if (lifecycle != Lifecycle.RUNNING) {
            throw unavailable(role, "Coordinator is not accepting new work");
        }
    }

    private void requireActive(Role role) {
        if (lifecycle != Lifecycle.RUNNING && lifecycle != Lifecycle.QUIESCING) {
            throw unavailable(role, "Coordinator is not active");
        }
    }

    private static OutboundStorageException invalid(Role role, String message) {
        return new OutboundStorageException(role, INVALID_TRANSITION, message);
    }

    private static OutboundStorageException unavailable(Role role, String message) {
        return new OutboundStorageException(role, UNAVAILABLE, message);
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

    private enum Lifecycle {
        NEW, RUNNING, QUIESCING, CLOSED
    }

    private static final class Attempt {
        private final CompletableFuture<Void> result = new CompletableFuture<>();
        private final CompletionStage<Void> view = result.minimalCompletionStage();
    }

    private static final class Context<M extends StandardMessage> {
        private final SelectionId id;
        private final Set<SourceId> sources;
        private Selected<M> routingTake;
        private Selected<M> lastRoutingReturn;
        private boolean routingTaken;
        private Assignment<M> assignment;
        private WorkId workId;
        private boolean markedRouted;
        private boolean finishing;
        private boolean handoffAccepted;
        private boolean destinationCompleted;
        private boolean pendingCompleted;
        private boolean selectedCompleted;
        private Attempt attempt;

        private Context(Selected<M> selected) {
            this.id = selected.id();
            this.sources = selected.sources();
        }
    }

    private static final class Execution<M extends StandardMessage> {
        private final Context<M> context;
        private final String destination;
        private Routed<M> take;
        private Routed<M> lastReturn;
        private boolean exposed;

        private Execution(Context<M> context, String destination) {
            this.context = context;
            this.destination = destination;
        }
    }
}
