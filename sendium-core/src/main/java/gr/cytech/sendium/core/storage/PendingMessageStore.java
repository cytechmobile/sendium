package gr.cytech.sendium.core.storage;

import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.outbound.OutboundWork.SourceId;

import java.util.Set;

public interface PendingMessageStore<M extends StandardMessage> extends OutboundStage {
    /**
     * Records accepted source state before protocol success. Later execution mutations must not
     * silently change that state. Repeating the same source ID while it remains pending is idempotent
     * and never overwrites its accepted content. A failure must
     * not be acknowledged. Deduplication after terminal source removal is not promised.
     */
    void admit(SourceId source, M message);

    /**
     * Called only after all work and required handoffs are terminal. Missing sources are already
     * complete. Partial multi-source completion is allowed; retrying the full set must be safe.
     */
    void complete(Set<SourceId> sources);
}
