package gr.cytech.sendium.core.outbound;

import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.outbound.OutboundWork.Destination;
import gr.cytech.sendium.core.outbound.OutboundWork.Routed;
import gr.cytech.sendium.core.outbound.OutboundWork.Selected;
import gr.cytech.sendium.core.outbound.OutboundWork.SelectionId;
import gr.cytech.sendium.core.outbound.OutboundWork.SourceId;
import gr.cytech.sendium.core.outbound.OutboundWork.WorkId;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionStage;

/**
 * Application-facing lifecycle boundary. Implementations receive pending, selected-router, and
 * routed-work stores as three direct constructor dependencies. Embedders own construction and
 * activation; this contract requires no CDI container. Store methods are implementation ports,
 * not an alternative way for workers to bypass coordinated completion.
 */
public interface OutboundCoordinator<M extends StandardMessage> extends AutoCloseable {
    /**
     * Opens selected stores before allowing admission; failed startup must clean up opened resources.
     * Future durable implementations reconcile by stable identity and resume the latest safely recorded
     * stage. Recorded routed work resumes at its worker without rerouting or repeating routing filters;
     * earlier retained records must not independently schedule the same work.
     */
    void start();

    /**
     * Begins shutdown by stopping new acceptance, selection, and takes. Existing routing, handoff,
     * completion, and return operations remain valid while the application drains and stops execution.
     * This does not drain providers, stop workers, or close stores. Idempotent.
     */
    void beginShutdown();

    /** Accepts source ownership and makes the message ready for selection according to the store policy. */
    void accept(SourceId source, M message);

    /** Accepts source ownership without making this source routable yet. */
    void acceptHeld(SourceId source, M message);

    /**
     * Makes already-accepted held sources available to bounded selection using one prepared execution message.
     * The caller decides which sources form an assembled message or an individual expired part.
     * This is an existing-work transition and remains available while shutting down; new admissions do not.
     */
    void makeHeldReady(Set<SourceId> sources, M message);

    /** Selects at most limit eligible pending items into the router backlog, returning the newly staged count. */
    int selectToRouter(int limit);

    Optional<Selected<M>> takeForRouting(Duration timeout) throws InterruptedException;

    /**
     * Requeues the current taken, unassigned selection for another routing attempt using its updated payload.
     * Use the original take handle. This does not repeat pending selection, choose a destination, or accept
     * new input; work with a retained destination intent cannot be requeued through this operation.
     */
    void requeueForRouting(Selected<M> selected);

    /**
     * Records one already-chosen destination before marking selected work routed. This operation does
     * not perform routing lookup, run routing filters, or send to the provider; dispatch uses takeFromRouted.
     * The selection and its source set must match work owned by this coordinator. Routing integration
     * must reject copied-route requests as UNSUPPORTED before assignment or dispatch, even if lookup
     * yields only one destination. It must not drop copies or submit several assignments for one selection.
     */
    Routed<M> recordToRouted(Selected<M> selected, Destination<M> destination);

    /** Takes already-routed work for the named destination without completing its stored ownership or sending it. */
    Optional<Routed<M>> takeFromRouted(String destination, Duration timeout) throws InterruptedException;

    /**
     * Returns the current unfinished take to routed scheduling at the same recorded destination.
     * Use the original take handle with its updated payload. Once terminal completion begins, retry
     * handoff/cleanup instead of returning the work for provider redispatch.
     */
    void returnToRouted(Routed<M> work);

    /**
     * Reports terminal processing of all provider parts for this work, conditional on successful required
     * handoff for all parts. The provider-processing integration aggregates part outcomes and handoffs.
     * Pass an already successful stage when no handoff is required. Failure or cancellation retains work
     * and source ownership; retrying completion must not redispatch provider work. The returned stage
     * completes after work completion and pending/selected cleanup. Duplicate callbacks are harmless,
     * including after cleanup, and may never complete unrelated work.
     */
    CompletionStage<Void> complete(WorkId work, CompletionStage<Void> requiredHandoff);

    /**
     * Terminal filter rejection/drop before a destination exists, with the same handoff rule as complete.
     * A routing miss is not terminal and must instead return to the router. Routed selections cannot
     * be discarded through this operation.
     */
    CompletionStage<Void> discard(SelectionId selection, CompletionStage<Void> requiredHandoff);

    /**
     * After beginShutdown and application-owned drain/stop/join, returns unfinished work to its owning stage and
     * closes stores. In-flight provider operations must first be resolved or drained by the application;
     * closing may not classify them as terminal. Closure is idempotent. Memory closure promises no
     * restart recovery, and a closed coordinator cannot be restarted.
     */
    @Override
    void close();
}
