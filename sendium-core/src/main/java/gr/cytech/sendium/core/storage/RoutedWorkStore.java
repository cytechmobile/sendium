package gr.cytech.sendium.core.storage;

import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.outbound.OutboundWork.Assignment;
import gr.cytech.sendium.core.outbound.OutboundWork.Completed;
import gr.cytech.sendium.core.outbound.OutboundWork.Routed;
import gr.cytech.sendium.core.outbound.OutboundWork.SelectionId;
import gr.cytech.sendium.core.outbound.OutboundWork.WorkId;

import java.time.Duration;
import java.util.Optional;

public interface RoutedWorkStore<M extends StandardMessage> extends OutboundStage {
    /**
     * Records the execution payload and one destination consistently before its work may be taken.
     * Future recovery resumes this recorded work at its worker, not through routing again.
     * While retained, repeating a selection returns its original assignment and work ID without reactivating
     * completed work or overwriting content. Callers retry with the same assignment;
     * a selection has exactly one recorded destination and work identity.
     * Conflicting source identities or destinations for an existing selection fail as INVALID_TRANSITION.
     * After forget, the coordinator must reject stale recording attempts using its lifecycle ownership.
     */
    Routed<M> record(Assignment<M> assignment);

    /**
     * Takes one work projection without completing it or silently changing earlier-stage records.
     * Zero timeout polls; negative timeouts are invalid. Destination must be nonblank.
     * A work item has at most one active taker.
     * Retain the returned Routed value as the local attempt handle when returning unfinished work.
     */
    Optional<Routed<M>> take(String destination, Duration timeout) throws InterruptedException;

    /**
     * Retains the recorded destination and updated execution projection when retrying or draining
     * in-flight work. Identity, selection, and destination must match the take. Repeated release cannot
     * duplicate work or reactivate completed work. Earlier-stage state cannot silently change;
     * preserving runtime mutations does not promise durable retry counters or timing.
     * Pass the take result itself with its message updated, not a reconstructed projection.
     */
    void release(Routed<M> work);

    /**
     * Called only after all provider parts and required handoffs for the active work are terminal.
     * Provider-part aggregation belongs to the provider-processing boundary, not separate routed items.
     * Returns the completed selection for source cleanup. Until forget, repeating completion of the terminal work
     * returns the same result so cleanup can be retried without sending again. Unknown IDs fail explicitly.
     */
    Completed complete(WorkId work);

    /**
     * Discards terminal bookkeeping only after pending and selected completion succeed. Nonterminal
     * selections cannot be forgotten; repeating forget for an absent selection is harmless.
     */
    void forget(SelectionId selection);
}
