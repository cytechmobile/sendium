package gr.cytech.sendium.core.outbound;

import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.outbound.OutboundWork.SourceId;
import gr.cytech.sendium.core.storage.OutboundStage;
import gr.cytech.sendium.core.storage.OutboundStorageException;

import java.util.Set;

/**
 * Explicitly composed DLR protection at the outbound admission boundary.
 * Existing durable or externally owned DLR handling needs no memory reservation.
 */
@FunctionalInterface
public interface OutboundDlrPolicy {
    OutboundDlrPolicy UNRESTRICTED = (source, message) -> Reservation.UNCHANGED;

    /**
     * Protects a source before pending publication. A retained source reuses its reservation.
     * Failure must prevent admission; implementations must not call pending storage here.
     */
    Reservation reserveAdmission(SourceId source, StandardMessage message);

    /** Only policies owning process-local admission holds need the bounded startup scan. */
    default boolean requiresRecoveryReservations() {
        return false;
    }

    /** Records the quiescent backlog count; true requests a second pass restoring all holds. */
    default boolean beginRecovery(long requiredSources) {
        throw new UnsupportedOperationException("dlrRecovery|error|reason:unsupportedRestoration");
    }

    /** Restores one source only when the complete backlog was established to fit. */
    default void restoreAccepted(SourceId source) {
        throw new UnsupportedOperationException("dlrRecovery|error|reason:unsupportedRestoration");
    }

    /** Protects a recovered/filtered execution's required source set atomically before provider I/O. */
    default void prepareProvider(String gatewayMsgId, Set<SourceId> requiredSources) {
        if (this != UNRESTRICTED && !requiredSources.isEmpty()) {
            throw new OutboundStorageException(OutboundStage.Role.ROUTED_WORK, OutboundStorageException.Reason.UNSUPPORTED,
                    "dlrProvider|error|reason:policyDoesNotProtectProviderWork");
        }
    }

    /** Original accepted requirements distinguish recovery from newly introduced filter requirements. */
    default void prepareProvider(String gatewayMsgId, Set<SourceId> requiredSources, Set<SourceId> acceptedDlrSources) {
        prepareProvider(gatewayMsgId, requiredSources);
    }

    /** Removes outbound ownership only after confirmed terminal handoff and SMS cleanup. */
    default void releaseOutbound(Set<SourceId> sources) {
    }

    /** Called only after confirmed SMS cleanup, including discard before provider preparation. */
    default void releaseOutbound(Set<SourceId> sources, Set<SourceId> acceptedDlrSources) {
        releaseOutbound(sources);
    }

    enum BlockReason { CAPACITY, MAXIMUM_TOO_SMALL, DISABLED, RECOVERY_PRIORITY }

    /** Expected policy backpressure, distinct from an internal/unavailable storage failure. */
    final class Blocked extends OutboundStorageException {
        private final BlockReason blockReason;
        private final int required;
        private final int maximum;

        public Blocked(BlockReason reason, int required, int maximum) {
            super(OutboundStage.Role.ROUTED_WORK, reason == BlockReason.DISABLED ? Reason.UNSUPPORTED : Reason.CAPACITY_EXCEEDED,
                    "dlrProvider|blocked|reason:" + reason + "|requiredSources:" + required +
                            "|maxTrackedSubmissions:" + maximum);
            this.blockReason = reason;
            this.required = required;
            this.maximum = maximum;
        }

        public BlockReason blockReason() {
            return blockReason;
        }

        public int required() {
            return required;
        }

        public int maximum() {
            return maximum;
        }
    }

    /** A newly acquired admission hold, not a general-purpose terminal capacity release. */
    @FunctionalInterface
    interface Reservation {
        Reservation UNCHANGED = () -> { };

        /**
         * Rolls back only this call's new hold, after the caller establishes source absence.
         * Reused/no-op reservations do nothing. Repeating rollback cannot affect a later acquisition.
         */
        void rollback();
    }
}
