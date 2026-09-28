package gr.cytech.sendium.core.storage;

import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.outbound.OutboundWork.Assignment;
import gr.cytech.sendium.core.outbound.OutboundWork.Completed;
import gr.cytech.sendium.core.outbound.OutboundWork.Destination;
import gr.cytech.sendium.core.outbound.OutboundWork.Routed;
import gr.cytech.sendium.core.outbound.OutboundWork.SelectionId;
import gr.cytech.sendium.core.outbound.OutboundWork.WorkId;

import java.time.Duration;
import java.util.Optional;

public interface RoutedWorkStore<M extends StandardMessage> extends OutboundStage {
    /**
     * Records the execution payload and one destination consistently before its work may be taken.
     * Future recovery resumes this recorded work at its worker, not through routing again.
     * Repeating a selection returns its original assignment and work ID without reactivating
     * completed/transferred work or overwriting content.
     * Callers retry with the same assignment; changing an active destination requires transfer, not
     * another assignment. There is at most one active destination work item per selection.
     * Conflicting source identities or destinations for an existing selection fail as INVALID_TRANSITION.
     */
    Routed<M> record(Assignment<M> assignment);

    /**
     * Takes one work projection without completing it or silently changing earlier-stage records.
     * Zero timeout polls; negative timeouts are invalid. Destination must be nonblank.
     * A work item has at most one active taker.
     */
    Optional<Routed<M>> take(String destination, Duration timeout) throws InterruptedException;

    /**
     * Retains the recorded destination and updated execution projection when retrying or draining
     * in-flight work. Identity, selection, and destination must match the take. Repeated release cannot
     * duplicate work or reactivate completed/transferred work. Earlier-stage state cannot silently change;
     * preserving runtime mutations does not promise durable retry counters or timing.
     */
    void release(Routed<M> work);

    /**
     * Atomically transfers active responsibility to exactly one successor with a new work ID under
     * the same selection. Retrying with the same previous ID returns the original successor without
     * reactivating it. The previous item is no longer schedulable and its late callbacks cannot complete
     * the successor. Transfer must not transiently complete the source or leave two active destinations.
     * A retry naming a different successor destination fails as INVALID_TRANSITION.
     */
    Routed<M> transfer(WorkId previous, Destination<M> destination);

    /**
     * Called only after all provider parts and required handoffs for the active work are terminal.
     * Provider-part aggregation belongs to the provider-processing boundary, not separate routed items.
     * Returns the completed selection for source cleanup. A transferred previous item returns empty,
     * even after its successor completes. Until forget, repeating completion of the terminal work
     * returns the same result so cleanup can be retried without sending again. Unknown IDs fail explicitly.
     */
    Optional<Completed> complete(WorkId work);

    /**
     * Discards terminal bookkeeping only after pending and selected completion succeed. Nonterminal
     * selections cannot be forgotten; repeating forget for an absent selection is harmless.
     */
    void forget(SelectionId selection);
}
