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
     * Stops new admission, selection, and takes. Existing routing, handoff, completion, and return
     * operations remain valid while the application stops and joins execution. Idempotent.
     */
    void quiesce();

    void admit(SourceId source, M message);

    int selectAndStage(int limit);

    Optional<Selected<M>> takeForRouting(Duration timeout) throws InterruptedException;

    /** Returns unfinished routing work; this is not a transition from a worker back to routing. */
    void returnToRouter(Selected<M> selected);

    /**
     * Records one destination before marking selected work routed; dispatch uses takeForDestination.
     * The selection and its source set must match work owned by this coordinator. Routing integration
     * must reject copied-route requests as UNSUPPORTED before assignment or dispatch, even if lookup
     * yields only one destination. It must not drop copies or submit several assignments for one selection.
     */
    Routed<M> route(Selected<M> selected, Destination<M> destination);

    Optional<Routed<M>> takeForDestination(String destination, Duration timeout) throws InterruptedException;

    void returnToDestination(Routed<M> work);

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
     * After quiesce and application-owned stop/join, returns unfinished work to its owning stage and
     * closes stores. In-flight provider operations must first be resolved or drained by the application;
     * closing may not classify them as terminal. Closure is idempotent. Memory closure promises no
     * restart recovery, and a closed coordinator cannot be restarted.
     */
    @Override
    void close();
}
