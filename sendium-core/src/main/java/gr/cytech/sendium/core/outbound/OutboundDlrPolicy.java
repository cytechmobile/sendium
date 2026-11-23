package gr.cytech.sendium.core.outbound;

import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.outbound.OutboundWork.SourceId;

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
