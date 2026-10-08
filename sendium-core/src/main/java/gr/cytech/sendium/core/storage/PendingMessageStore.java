package gr.cytech.sendium.core.storage;

import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.outbound.OutboundWork.SourceId;

import java.util.Set;

public interface PendingMessageStore<M extends StandardMessage> extends OutboundStage {
    /**
     * Records accepted source state before protocol success. Later execution mutations must not
     * silently change that state. Repeating the same source ID while it remains pending is idempotent
     * and never overwrites its accepted content. A failure must
     * not be acknowledged. This admits ready work, subject to the selection eligibility policy.
     * Repeating either admission method preserves the first accepted payload and held/ready disposition.
     * Deduplication after terminal source removal is not promised.
     */
    void admit(SourceId source, M message);

    /** Retains an accepted source without making it eligible for selection until makeHeldReady succeeds. */
    void admitHeld(SourceId source, M message);

    /**
     * Makes held sources ready using one execution message referring to their nonempty source set. Original
     * source records remain authoritative. Publication must bind the whole set before exposing work
     * to selection; it does not bypass selection limits or create another accepted source.
     * While all sources remain retained, repeating the exact source set preserves the first published
     * payload and cannot duplicate work, including after selection/routing. Missing, ordinarily admitted,
     * or differently grouped sources fail explicitly. The caller owns assembly/expiry policy.
     */
    void makeHeldReady(Set<SourceId> sources, M message);

    /**
     * Called only after all work and required handoffs are terminal. Missing sources are already
     * complete. Partial multi-source completion is allowed; retrying the full set must be safe.
     */
    void complete(Set<SourceId> sources);
}
