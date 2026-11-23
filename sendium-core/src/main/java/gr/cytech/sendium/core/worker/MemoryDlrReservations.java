package gr.cytech.sendium.core.worker;

import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.outbound.OutboundDlrPolicy;
import gr.cytech.sendium.core.outbound.OutboundWork.SourceId;
import gr.cytech.sendium.core.storage.OutboundStage;
import gr.cytech.sendium.core.storage.OutboundStorageException;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Source-keyed memory admission holds. No SMS payloads or storage callbacks are retained.
 * This owner will also serialize memory receipt state; its lock must never span external I/O.
 */
public final class MemoryDlrReservations implements OutboundDlrPolicy {
    final ReentrantLock lock = new ReentrantLock();
    private final int maximum;
    private final Map<SourceId, Object> outboundHolds = new HashMap<>();

    public MemoryDlrReservations(int maximum) {
        if (maximum <= 0) {
            throw new IllegalArgumentException("dlrAdmission|error|reason:nonPositiveCapacity");
        }
        this.maximum = maximum;
    }

    @Override
    public Reservation reserveAdmission(SourceId source, StandardMessage message) {
        Objects.requireNonNull(source, "dlrAdmission|error|reason:missingSource");
        Objects.requireNonNull(message, "dlrAdmission|error|reason:missingMessage");
        if (message.dlrReturnMetadata == null) {
            return Reservation.UNCHANGED;
        }
        lock.lock();
        try {
            if (outboundHolds.containsKey(source)) {
                return Reservation.UNCHANGED;
            }
            if (outboundHolds.size() >= maximum) {
                throw new OutboundStorageException(OutboundStage.Role.PENDING,
                        OutboundStorageException.Reason.CAPACITY_EXCEEDED,
                        "dlrAdmission|rejected|reason:capacityExceeded|maxTrackedSubmissions:" + maximum);
            }
            // Identity, rather than just the source key, fences a delayed rollback after reacquisition.
            Object hold = new Object();
            outboundHolds.put(source, hold);
            return () -> rollback(source, hold);
        } finally {
            lock.unlock();
        }
    }

    private void rollback(SourceId source, Object hold) {
        lock.lock();
        try {
            outboundHolds.remove(source, hold);
        } finally {
            lock.unlock();
        }
    }

    public int usedCapacity() {
        lock.lock();
        try {
            return outboundHolds.size();
        } finally {
            lock.unlock();
        }
    }

    public int maxCapacity() {
        return maximum;
    }
}
