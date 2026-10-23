package gr.cytech.sendium.core.storage;

import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.outbound.OutboundWork.Selected;
import gr.cytech.sendium.core.outbound.OutboundWork.SelectionId;

import java.time.Duration;
import java.util.Optional;

/**
 * The implementation receives its pending source and selection/preparation policy through composition.
 * Selection is not constrained to scanning Sendium-owned records or exposing SQL through this API.
 */
public interface SelectedRouterStore<M extends StandardMessage> extends OutboundStage {
    /**
     * Selects at most limit eligible, priority-aware items into the router backlog, returning the newly staged count.
     * Limit must be positive. Selected, taken, and routed sources are ineligible for another selection.
     * Source ownership is retained, including after partial publication failure. Stable selection IDs
     * allow retry/reconciliation without repeating already-committed preparation. No cross-store
     * transaction is implied. The implementation must not load the entire pending backlog.
     */
    int selectToRouter(int limit);

    /**
     * Takes an execution projection without completing selected or pending ownership.
     * Execution mutations must not silently modify retained earlier-stage state.
     * Zero timeout polls; negative timeouts are invalid. An item has at most one active taker.
     * Retain the returned Selected value as the local attempt handle when returning unfinished work.
     */
    Optional<Selected<M>> take(Duration timeout) throws InterruptedException;

    /**
     * Returns taken, not-yet-routed work with its updated execution projection, without silently changing
     * earlier-stage state. Identity/source ownership must match the take. Repeated release cannot enqueue
     * duplicates. Preserving runtime mutations does not promise durable retry counters or timing.
     * Pass the take result itself with its message updated, not a reconstructed or unrelated projection.
     */
    void release(Selected<M> selected);

    /**
     * Stops router scheduling after the single destination has been recorded. This is idempotent and
     * retains selected state through terminal processing, including for a future durable router
     * combined with memory-backed destinations. It does not complete the pending source.
     */
    void markRouted(SelectionId selection);

    /**
     * Removes selected ownership after terminal processing and pending completion. Idempotent;
     * never use this to signal router dequeue or initial destination dispatch.
     */
    void complete(SelectionId selection);
}
