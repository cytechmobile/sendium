package gr.cytech.sendium.core.storage.internal;

import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.outbound.OutboundWork.Selected;
import gr.cytech.sendium.core.outbound.OutboundWork.SourceId;
import gr.cytech.sendium.core.storage.OutboundStage;

import java.time.Instant;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * Internal composition boundary for the volatile selected queue, not a new stage/storage contract.
 * Except status and lock/condition access, calls require the shared lock. Implementations retain
 * accepted ownership and publish one detached selection before removing its waiting reference.
 */
public interface PendingSelectionSource<M extends StandardMessage> {
    ReentrantLock selectionLock();

    Condition selectionChanged();

    OutboundStage.Status status();

    void requireReady(OutboundStage.Role role);

    void attachSelector(Object owner);

    boolean stageNext(Instant now, Consumer<Selected<M>> publication);

    M snapshot(M message, OutboundStage.Role role);

    boolean contains(SourceId source);
}
