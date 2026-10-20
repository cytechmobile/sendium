package gr.cytech.sendium.core.storage;

import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.outbound.OutboundWork.SourceId;
import gr.cytech.sendium.core.storage.pending.MultipartMembership;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface PendingMessageStore<M extends StandardMessage> extends OutboundStage {
    /**
     * Records accepted source state before protocol success. Later execution mutations must not
     * silently change that state. Repeating the same source ID while it remains pending is idempotent
     * and never overwrites its accepted content. A failure must
     * not be acknowledged. This admits ready work, subject to the selection eligibility policy.
     * Repeating either admission method preserves the first accepted payload and held/ready disposition.
     * Deduplication after terminal source removal is not promised.
     * Returns the supplied stable source handle only after backend acceptance; never returns null
     * or substitutes another source ID. Callers must use this result before acknowledging success.
     */
    SourceId admit(SourceId source, M message);

    /** Retains an accepted source without making it eligible for selection until makeHeldReady succeeds. */
    SourceId admitHeld(SourceId source, M message);

    /**
     * Atomically accepts a held part with its immutable membership before acknowledgement. Every new source ID
     * is independent: fill the oldest unexpired open matching group missing its ordinal, or create a new group.
     * Creation order breaks ties. The first accepted part fixes the group's absolute deadline using timeout;
     * later arrivals never extend it. An internal retry of a retained multipart source returns its original membership.
     * A source already admitted without multipart membership cannot be converted through this method.
     */
    MultipartMembership admitPart(SourceId source, M message, Duration timeout);

    /** Returns recorded membership for a retained multipart source; ordinary or absent sources have no membership. */
    Optional<MultipartMembership> findMultipart(SourceId source);

    /** Returns an isolated accepted payload, or absence established by a ready backend; failures are not absence. */
    Optional<M> find(SourceId source);

    /**
     * Returns at most limit retained source handles, including held/selected/routed sources, in UUID.compareTo
     * order strictly after afterExclusive. Pass null to start; continue from the last returned ID, even if that
     * source has since completed. An empty page ends traversal. limit must be positive. Pages are immutable.
     * This is bounded recovery access, not selection or a payload backlog copy. A quiescent scan visits all
     * retained sources once. Concurrent admission at/before the cursor may be missed; completed sources may
     * disappear before find. Startup recovery must quiesce admission/processing rather than assume a snapshot.
     */
    List<SourceId> sourcesAfter(SourceId afterExclusive, int limit);

    /**
     * Makes held sources ready using one execution message referring to their nonempty source set. Original
     * source records remain authoritative. Publication must bind the whole set before exposing work
     * to selection; it does not bypass selection limits or create another accepted source.
     * While all sources remain retained, repeating the exact source set preserves the first published
     * payload and cannot duplicate work, including after selection/routing. Missing, ordinarily admitted,
     * or differently grouped sources fail explicitly. The caller owns assembly/expiry policy.
     * Assigned multipart sources may be published together only as their complete recorded group;
     * partial/expired assigned parts are published individually, never mixed with another group or unassigned held sources.
     */
    void makeHeldReady(Set<SourceId> sources, M message);

    /**
     * Called only after all work and required handoffs are terminal. Successful return confirms that
     * every requested source is absent. Missing sources count as complete only when a ready backend can
     * establish their absence; unavailability, corrupt state, or inaccessible ownership must fail explicitly.
     * A failed call may have removed some or all requested sources before its result became uncertain.
     * Retrying the full source set must therefore be safe; a failure must never be treated as established absence.
     */
    void complete(Set<SourceId> sources);
}
