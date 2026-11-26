package gr.cytech.sendium.core.worker;

import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.outbound.OutboundDlrPolicy;
import gr.cytech.sendium.core.outbound.OutboundWork.SourceId;
import gr.cytech.sendium.core.storage.OutboundStage;
import gr.cytech.sendium.core.storage.OutboundStorageException;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Source-keyed memory admission holds. No SMS payloads or storage callbacks are retained.
 * This owner will also serialize memory receipt state; its lock must never span external I/O.
 */
public final class MemoryDlrReservations implements OutboundDlrPolicy {
    final ReentrantLock lock = new ReentrantLock();
    private final int maximum;
    private final Map<SourceId, Source> sources = new HashMap<>();
    private final Map<String, Execution> executions = new HashMap<>();
    private boolean attached;
    private boolean closed;
    private Throwable failure;
    private boolean recoveryStarted;
    private long unprotectedAccepted;

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
        lock.lock();
        try {
            verifyAvailable();
            if (message.dlrReturnMetadata == null || sources.containsKey(source)) {
                return Reservation.UNCHANGED;
            }
            if (unprotectedAccepted != 0) {
                throw new OutboundStorageException(OutboundStage.Role.PENDING,
                        OutboundStorageException.Reason.CAPACITY_EXCEEDED,
                        "dlrAdmission|rejected|reason:acceptedRecoveryPending|unprotectedSources:" + unprotectedAccepted);
            }
            if (sources.size() >= maximum) {
                throw new OutboundStorageException(OutboundStage.Role.PENDING,
                        OutboundStorageException.Reason.CAPACITY_EXCEEDED,
                        "dlrAdmission|rejected|reason:capacityExceeded|maxTrackedSubmissions:" + maximum);
            }
            // Identity, rather than just the source key, fences a delayed rollback after reacquisition.
            var hold = new Source();
            sources.put(source, hold);
            return () -> rollback(source, hold);
        } finally {
            lock.unlock();
        }
    }

    private void rollback(SourceId source, Source hold) {
        lock.lock();
        try {
            if (hold.executions.isEmpty()) {
                sources.remove(source, hold);
            }
        } finally {
            lock.unlock();
        }
    }

    public int usedCapacity() {
        lock.lock();
        try {
            return sources.size();
        } finally {
            lock.unlock();
        }
    }

    public int maxCapacity() {
        return maximum;
    }

    @Override
    public boolean requiresRecoveryReservations() {
        return true;
    }

    @Override
    public boolean beginRecovery(long requiredSources) {
        lock.lock();
        try {
            verifyAvailable();
            if (requiredSources < 0 || recoveryStarted || !sources.isEmpty() || !executions.isEmpty()) {
                throw new DlrStorageException("dlrRecovery|error|reason:invalidInitialOwnership");
            }
            recoveryStarted = true;
            unprotectedAccepted = requiredSources;
            return requiredSources <= maximum;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void restoreAccepted(SourceId source) {
        Objects.requireNonNull(source, "dlrRecovery|error|reason:missingSource");
        lock.lock();
        try {
            verifyAvailable();
            if (!recoveryStarted || unprotectedAccepted <= 0 || sources.containsKey(source) || sources.size() >= maximum) {
                throw new DlrStorageException("dlrRecovery|error|reason:invalidRestoration");
            }
            sources.put(source, new Source());
            unprotectedAccepted--;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void prepareProvider(String gatewayMsgId, Set<SourceId> requiredSources) {
        prepareProvider(gatewayMsgId, requiredSources, Set.of());
    }

    @Override
    public void prepareProvider(String gatewayMsgId, Set<SourceId> requiredSources, Set<SourceId> acceptedDlrSources) {
        Set<SourceId> required = Set.copyOf(requiredSources);
        Set<SourceId> accepted = Set.copyOf(acceptedDlrSources);
        if (!required.containsAll(accepted)) {
            throw new DlrStorageException("dlrRecovery|error|reason:conflictingAcceptedSources");
        }
        if (required.isEmpty()) {
            return;
        }
        String id = gatewayId(gatewayMsgId);
        lock.lock();
        try {
            verifyAvailable();
            if (required.size() > maximum) {
                throw new Blocked(BlockReason.MAXIMUM_TOO_SMALL, required.size(), maximum);
            }
            var existing = executions.get(id);
            if (existing != null && !existing.sources.equals(required)) {
                throw new DlrStorageException("memoryDlrOwnership|error|reason:conflictingSourceSet|gatewayMsgId:" + id);
            }
            long missing = required.stream().filter(source -> !sources.containsKey(source)).count();
            long recovered = unprotectedAccepted == 0 ? 0 :
                    accepted.stream().filter(source -> !sources.containsKey(source)).count();
            if (unprotectedAccepted != 0 && missing != recovered) {
                throw new Blocked(BlockReason.RECOVERY_PRIORITY, required.size(), maximum);
            }
            if (missing > maximum - sources.size()) {
                throw new Blocked(BlockReason.CAPACITY, required.size(), maximum);
            }
            verifyRecoveryDebit(recovered);
            // No mutation until the entire required set fits. In particular, recovery never
            // holds a subset while waiting for another recovered execution to release capacity.
            for (SourceId source : required) {
                sources.computeIfAbsent(source, _ -> new Source()).outbound = true;
            }
            associateExecution(id, required);
            unprotectedAccepted -= recovered;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Binds a receipt/execution ID to the coordinator's complete source set, not receipt return IDs.
     * All entries must already be protected; association never silently allocates capacity.
     */
    public void associateExecution(String gatewayId, Set<SourceId> sourceIds) {
        String id = gatewayId(gatewayId);
        Set<SourceId> required = Set.copyOf(sourceIds);
        if (required.isEmpty()) {
            throw new DlrStorageException("memoryDlrOwnership|error|reason:emptySourceSet");
        }
        lock.lock();
        try {
            verifyAvailable();
            var existing = executions.get(id);
            if (existing != null) {
                if (!existing.sources.equals(required)) {
                    throw new DlrStorageException("memoryDlrOwnership|error|reason:conflictingSourceSet|gatewayMsgId:" + id);
                }
                return;
            }
            if (required.stream().anyMatch(source -> !sources.containsKey(source))) {
                throw new DlrStorageException("memoryDlrOwnership|error|reason:missingReservation|gatewayMsgId:" + id);
            }
            var execution = new Execution(id, required);
            executions.put(id, execution);
            required.forEach(source -> sources.get(source).executions.add(execution));
        } finally {
            lock.unlock();
        }
    }

    /** Called after required outbound handoff/cleanup, never just after an early receipt. */
    @Override
    public void releaseOutbound(Set<SourceId> sourceIds) {
        releaseOutbound(sourceIds, Set.of());
    }

    @Override
    public void releaseOutbound(Set<SourceId> sourceIds, Set<SourceId> acceptedDlrSources) {
        Set<SourceId> completed = Set.copyOf(sourceIds);
        Set<SourceId> accepted = Set.copyOf(acceptedDlrSources);
        lock.lock();
        try {
            verifyAvailable();
            if (!completed.containsAll(accepted)) {
                throw new DlrStorageException("dlrRecovery|error|reason:conflictingCompletedSources");
            }
            long removed = unprotectedAccepted == 0 ? 0 :
                    accepted.stream().filter(source -> !sources.containsKey(source)).count();
            verifyRecoveryDebit(removed);
            var affected = new HashSet<Execution>();
            for (SourceId id : completed) {
                var source = sources.get(id);
                if (source != null) {
                    source.outbound = false;
                    affected.addAll(source.executions);
                    if (source.executions.isEmpty()) {
                        sources.remove(id);
                    }
                }
            }
            affected.forEach(this::retireIfFinished);
            unprotectedAccepted -= removed;
        } finally {
            lock.unlock();
        }
    }

    private void verifyRecoveryDebit(long count) {
        if (count > unprotectedAccepted) {
            throw new DlrStorageException("dlrRecovery|error|reason:inconsistentOutstandingCount");
        }
    }

    // Backend-only operations below run under this same owner lock; they invoke no callbacks.
    void attach() {
        verifyAvailable();
        if (attached) {
            throw new DlrStorageException("memoryDlrOwnership|error|reason:ownerAlreadyAttached");
        }
        attached = true;
    }

    Execution execution(String id) {
        var execution = executions.get(id);
        if (execution == null) {
            throw new DlrStorageException("memoryDlrOwnership|error|reason:missingExecution|gatewayMsgId:" + id);
        }
        return execution;
    }

    void releaseDlr(Execution execution) {
        execution.receiptHeld = false;
        retireIfFinished(execution);
    }

    private void retireIfFinished(Execution execution) {
        if (execution.receiptHeld || execution.sources.stream().anyMatch(id -> sources.get(id).outbound)) {
            return;
        }
        executions.remove(execution.id);
        for (SourceId id : execution.sources) {
            var source = sources.get(id);
            source.executions.remove(execution);
            if (!source.outbound && source.executions.isEmpty()) {
                sources.remove(id);
            }
        }
    }

    void verifyAvailable() {
        if (closed) {
            throw new DlrStorageException("memoryDlr|error|reason:closed");
        }
        if (failure != null) {
            throw new DlrStorageException("memoryDlr|error|reason:internalFailure", failure);
        }
    }

    void fail(Throwable cause) {
        if (failure == null) {
            failure = cause;
        }
    }

    void close() {
        closed = true;
        sources.values().forEach(source -> source.executions.clear());
        executions.values().forEach(execution -> execution.firstCorrelations.clear());
        sources.clear();
        executions.clear();
        failure = null;
    }

    static String gatewayId(String value) {
        try {
            return UUID.fromString(Objects.requireNonNull(value)).toString();
        } catch (IllegalArgumentException | NullPointerException failure) {
            throw new DlrStorageException("memoryDlr|error|reason:invalidGatewayId", failure);
        }
    }

    static final class Execution {
        final String id;
        final Set<SourceId> sources;
        final Map<ProviderKey, Long> firstCorrelations = new HashMap<>();
        boolean receiptHeld;
        boolean terminal;

        Execution(String id, Set<SourceId> sources) {
            this.id = id;
            this.sources = sources;
        }
    }

    record ProviderKey(String provider, String messageId) { }

    private static final class Source {
        private boolean outbound = true;
        private final Set<Execution> executions = new HashSet<>();
    }
}
