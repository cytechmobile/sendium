package gr.cytech.sendium.core.worker;

import gr.cytech.sendium.core.worker.MemoryDlrReservations.Execution;
import gr.cytech.sendium.core.worker.MemoryDlrReservations.ProviderKey;
import gr.cytech.sendium.core.worker.MessageState.DeliveryChannel;
import gr.cytech.sendium.core.worker.MessageState.DeliveryStatus;
import gr.cytech.sendium.core.worker.MessageState.MessageStatus;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Process-local receipts and indexes protected by the admission capacity owner's lock.
 * No network calls, futures, completion listeners or pending-storage callbacks run here.
 */
final class MemoryDlrStorage implements DlrStorageBackend {
    private static final int MAX_BATCH = 1_000;
    private static final Comparator<Expiry> EXPIRY_ORDER = Comparator.comparingLong(Expiry::at)
            .thenComparing(Expiry::kind).thenComparing(Expiry::first).thenComparing(Expiry::second);
    private static final Comparator<Due> DUE_ORDER = Comparator.comparingLong(Due::at).thenComparing(Due::id);
    private static final Comparator<Receipt> SMPP_ORDER = Comparator
            .comparingLong((Receipt receipt) -> receipt.state.getResolvedAt())
            .thenComparingLong(receipt -> receipt.createdAt).thenComparing(receipt -> receipt.execution.id);

    private final MemoryDlrReservations owner;
    private final Clock clock;
    private final long correlationTtl;
    private final long deliveryTtl;
    private final long claimDuration;
    private final Map<String, Receipt> receipts = new HashMap<>();
    private final Map<ProviderKey, Correlation> correlations = new HashMap<>();
    private final NavigableSet<Expiry> expirations = new TreeSet<>(EXPIRY_ORDER);
    private final NavigableSet<Due> httpDue = new TreeSet<>(DUE_ORDER);
    private final Map<String, NavigableSet<Receipt>> smppPending = new HashMap<>();
    private long waiting;
    private long pending;
    private long failed;

    MemoryDlrStorage(MemoryDlrReservations owner, Duration correlationTtl, Duration deliveryTtl,
                     Duration claimDuration, Clock clock) {
        this.owner = Objects.requireNonNull(owner, "memoryDlr|error|reason:missingOwner");
        this.clock = Objects.requireNonNull(clock, "memoryDlr|error|reason:missingClock");
        this.correlationTtl = positiveMillis(correlationTtl);
        this.deliveryTtl = positiveMillis(deliveryTtl);
        this.claimDuration = positiveMillis(claimDuration);
        locked(() -> {
            owner.attach();
            return null;
        });
    }

    @Override
    public String backend() {
        return "memory";
    }

    @Override
    public void verifyAvailable() {
        locked(() -> null);
    }

    @Override
    public void recordProviderAccepted(MessageState state, String providerName, String providerMessageId) {
        MessageState prepared = prepare(state);
        ProviderKey key = providerKey(providerName, providerMessageId);
        locked(() -> {
            String id = MemoryDlrReservations.gatewayId(prepared.getGatewayMsgId());
            Execution execution = owner.execution(id);
            if (execution.terminal) {
                throw new DlrStorageException("memoryDlrAcceptance|error|reason:terminalStateExists|gatewayMsgId:" + id);
            }
            long now = clock.millis();
            long expires = execution.firstCorrelations.containsKey(key) ?
                    execution.firstCorrelations.get(key) : deadline(now, correlationTtl);
            // An expired handoff retry is acknowledged idempotently, never given a new TTL.
            if (expires <= now) {
                return null;
            }
            prepared.setProviderName(providerName);
            prepared.setProviderMessageId(providerMessageId);
            prepared.setStatus(MessageStatus.SENT);
            prepared.setTimestamp(now);

            Receipt receipt = receipts.get(id);
            if (receipt == null) {
                receipt = new Receipt(execution, prepared, now);
                receipts.put(id, receipt);
                execution.receiptHeld = true;
                waiting++;
            } else {
                receipt.state = prepared;
                removeExpiry(receipt);
            }
            var previous = correlations.get(key);
            if (previous != null) {
                unlinkCorrelation(key, previous);
                if (previous.receipt != receipt && previous.receipt.aliases.isEmpty()) {
                    setExpiry(previous.receipt, previous.expiry.at());
                }
                if (previous.receipt != receipt &&
                        key.provider().equals(previous.receipt.state.getProviderName()) &&
                        key.messageId().equals(previous.receipt.state.getProviderMessageId())) {
                    previous.receipt.state.setProviderName(null);
                    previous.receipt.state.setProviderMessageId(null);
                }
            }
            var expiry = new Expiry(expires, ExpiryKind.CORRELATION, providerName, providerMessageId);
            var correlation = new Correlation(receipt, expiry);
            correlations.put(key, correlation);
            receipt.aliases.put(key, correlation);
            expirations.add(expiry);
            execution.firstCorrelations.putIfAbsent(key, expires);
            return null;
        });
    }

    @Override
    public Optional<MessageState> recordProviderRejected(MessageState state, String providerName,
                                                         String providerMessageId, int dlrState, String errorCode) {
        MessageState prepared = prepare(state);
        if (providerMessageId != null && !providerMessageId.isBlank()) {
            providerKey(providerName, providerMessageId);
            prepared.setProviderName(providerName);
            prepared.setProviderMessageId(providerMessageId);
        }
        return locked(() -> {
            String id = MemoryDlrReservations.gatewayId(prepared.getGatewayMsgId());
            Execution execution = owner.execution(id);
            if (execution.terminal) {
                return Optional.empty();
            }
            long now = clock.millis();
            final long expires = deadline(now, deliveryTtl);
            Receipt receipt = receipts.get(id);
            if (receipt == null) {
                receipt = new Receipt(execution, prepared, now);
                receipts.put(id, receipt);
                execution.receiptHeld = true;
                waiting++;
            }
            prepared.setStatus(MessageStatus.FAILED);
            prepared.setDlrState(dlrState);
            prepared.setErrorCode(errorCode);
            prepared.setLastDeliveryResult("provider_rejected");
            receipt.state = prepared;
            makePending(receipt, now, expires);
            return Optional.of(snapshot(receipt.state));
        });
    }

    @Override
    public Optional<MessageState> resolveDlr(String providerName, String providerMessageId,
                                            MessageStatus status, int dlrState, String errorCode) {
        Objects.requireNonNull(status, "memoryDlr|error|reason:missingStatus");
        ProviderKey key = providerKey(providerName, providerMessageId);
        return locked(() -> {
            if (!DlrService.isTerminalDlrState(dlrState)) {
                return Optional.empty();
            }
            var correlation = correlations.get(key);
            if (correlation == null) {
                return Optional.empty();
            }
            long now = clock.millis();
            if (correlation.expiry.at() <= now) {
                expireCorrelation(key, correlation);
                return Optional.empty();
            }
            final long expires = deadline(now, deliveryTtl);
            Receipt receipt = correlation.receipt;
            receipt.state.setProviderName(providerName);
            receipt.state.setProviderMessageId(providerMessageId);
            receipt.state.setStatus(status);
            receipt.state.setDlrState(dlrState);
            receipt.state.setErrorCode(errorCode);
            makePending(receipt, now, expires);
            return Optional.of(snapshot(receipt.state));
        });
    }

    @Override
    public List<ProviderDlrEvent.Result> applyProviderBatch(List<ProviderDlrEvent> events) {
        List<ProviderDlrEvent> batch = List.copyOf(events);
        if (batch.size() > MAX_BATCH) {
            throw new IllegalArgumentException("memoryDlrBatch|error|reason:batchTooLarge");
        }
        return locked(() -> {
            var results = new ArrayList<ProviderDlrEvent.Result>(batch.size());
            for (ProviderDlrEvent event : batch) {
                try {
                    Optional<MessageState> state = switch (event) {
                        case ProviderDlrEvent.Accepted a -> {
                            recordProviderAccepted(a.message(), a.providerName(), a.providerMessageId());
                            yield Optional.empty();
                        }
                        case ProviderDlrEvent.Rejected r -> recordProviderRejected(r.message(), r.providerName(),
                                r.providerMessageId(), r.dlrState(), r.errorCode());
                        case ProviderDlrEvent.Receipt r -> resolveDlr(r.providerName(), r.providerMessageId(),
                                r.status(), r.dlrState(), r.errorCode());
                    };
                    results.add(ProviderDlrEvent.Result.success(state));
                } catch (DlrStorageException failure) {
                    results.add(new ProviderDlrEvent.Result(Optional.empty(), failure));
                }
            }
            return List.copyOf(results);
        });
    }

    private void makePending(Receipt receipt, long now, long expires) {
        clearCorrelations(receipt);
        waiting--;
        pending++;
        receipt.execution.terminal = true;
        receipt.execution.firstCorrelations.clear();
        receipt.state.setDeliveryStatus(DeliveryStatus.PENDING);
        receipt.state.setResolvedAt(now);
        receipt.state.setTimestamp(now);
        receipt.state.setNextAttemptAt(receipt.state.getDeliveryChannel() == DeliveryChannel.HTTP ? now : null);
        setExpiry(receipt, expires);
        if (receipt.state.getDeliveryChannel() == DeliveryChannel.HTTP) {
            scheduleHttp(receipt);
        } else {
            smppPending.computeIfAbsent(receipt.state.getSystemId(), _ -> new TreeSet<>(SMPP_ORDER)).add(receipt);
        }
    }

    @Override
    public Optional<MessageState> getState(String gatewayMsgId) {
        String id = MemoryDlrReservations.gatewayId(gatewayMsgId);
        return locked(() -> live(receipts.get(id), clock.millis()).map(receipt -> snapshot(receipt.state)));
    }

    @Override
    public List<MessageState> listPendingSmppDeliveries(String systemId) {
        return locked(() -> {
            if (systemId == null || systemId.isBlank()) {
                return List.of();
            }
            var indexed = smppPending.get(systemId);
            if (indexed == null) {
                return List.of();
            }
            long now = clock.millis();
            var states = new ArrayList<MessageState>();
            for (Receipt receipt : indexed) {
                if (receipt.expiry.at() > now) {
                    states.add(snapshot(receipt.state));
                }
            }
            return List.copyOf(states);
        });
    }

    @Override
    public List<MessageState> claimDueHttpDeliveries(int limit) {
        int bound = batchBound(limit);
        return locked(() -> {
            long now = clock.millis();
            var states = new ArrayList<MessageState>();
            for (int inspected = 0; inspected < bound && !httpDue.isEmpty() && httpDue.first().at() <= now; inspected++) {
                Receipt receipt = receipts.get(httpDue.first().id());
                if (live(receipt, now).isPresent()) {
                    startAttempt(receipt, now);
                    states.add(snapshot(receipt.state));
                } else {
                    removeReceipt(receipt);
                }
            }
            return List.copyOf(states);
        });
    }

    @Override
    public Optional<MessageState> startDeliveryAttempt(String gatewayMsgId, DeliveryChannel expectedChannel) {
        if (expectedChannel == null || expectedChannel == DeliveryChannel.NONE) {
            throw new IllegalArgumentException("memoryDlrDelivery|error|reason:invalidChannel");
        }
        String id = MemoryDlrReservations.gatewayId(gatewayMsgId);
        return locked(() -> {
            long now = clock.millis();
            var receipt = live(receipts.get(id), now).orElse(null);
            if (receipt == null || receipt.state.getDeliveryStatus() != DeliveryStatus.PENDING ||
                    receipt.state.getDeliveryChannel() != expectedChannel ||
                    receipt.claimedUntil != null && receipt.claimedUntil > now) {
                return Optional.empty();
            }
            startAttempt(receipt, now);
            return Optional.of(snapshot(receipt.state));
        });
    }

    private void startAttempt(Receipt receipt, long now) {
        final long until = deadline(now, claimDuration);
        if (receipt.state.getDeliveryAttemptCount() == Integer.MAX_VALUE) {
            throw new DlrStorageException("memoryDlrDelivery|error|reason:attemptOverflow");
        }
        receipt.state.setDeliveryAttemptCount(receipt.state.getDeliveryAttemptCount() + 1);
        receipt.state.setLastAttemptAt(now);
        receipt.state.setTimestamp(now);
        receipt.claimedUntil = until;
        scheduleHttp(receipt);
    }

    @Override
    public boolean completeDelivery(String gatewayMsgId, int expectedAttempt) {
        return finishAttempt(gatewayMsgId, expectedAttempt, receipt -> {
            removeReceipt(receipt);
            return true;
        });
    }

    @Override
    public boolean retryDelivery(String gatewayMsgId, int expectedAttempt, String result, long nextAttemptAt) {
        return finishAttempt(gatewayMsgId, expectedAttempt, receipt -> {
            receipt.state.setLastDeliveryResult(normalize(result));
            receipt.state.setNextAttemptAt(nextAttemptAt);
            receipt.claimedUntil = null;
            scheduleHttp(receipt);
            return true;
        });
    }

    @Override
    public boolean failDelivery(String gatewayMsgId, int expectedAttempt, String result) {
        return finishAttempt(gatewayMsgId, expectedAttempt, receipt -> {
            removeDeliveryIndex(receipt);
            receipt.state.setDeliveryStatus(DeliveryStatus.FAILED);
            receipt.state.setLastDeliveryResult(normalize(result));
            receipt.state.setNextAttemptAt(null);
            receipt.claimedUntil = null;
            pending--;
            failed++;
            return true;
        });
    }

    @Override
    public boolean failInvalidDelivery(String gatewayMsgId, int expectedAttempt, String result) {
        return failDelivery(gatewayMsgId, expectedAttempt, result);
    }

    private boolean finishAttempt(String gatewayMsgId, int expectedAttempt, Predicate<Receipt> operation) {
        if (expectedAttempt < 1) {
            throw new IllegalArgumentException("memoryDlrDelivery|error|reason:nonPositiveAttempt");
        }
        String id = MemoryDlrReservations.gatewayId(gatewayMsgId);
        return locked(() -> {
            long now = clock.millis();
            var receipt = live(receipts.get(id), now).orElse(null);
            if (receipt == null || receipt.state.getDeliveryStatus() != DeliveryStatus.PENDING ||
                    receipt.state.getDeliveryAttemptCount() != expectedAttempt) {
                return false;
            }
            receipt.state.setTimestamp(now);
            return operation.test(receipt);
        });
    }

    /** Bounded by expiry-index entries, with no full-backlog scan or stale retry queue nodes. */
    int cleanup(int limit) {
        int bound = batchBound(limit);
        return locked(() -> {
            int processed = 0;
            long now = clock.millis();
            while (processed < bound && !expirations.isEmpty() && expirations.first().at() <= now) {
                var expiry = expirations.first();
                if (expiry.kind() == ExpiryKind.CORRELATION) {
                    var key = new ProviderKey(expiry.first(), expiry.second());
                    expireCorrelation(key, correlations.get(key));
                } else {
                    removeReceipt(receipts.get(expiry.first()));
                }
                processed++;
            }
            return processed;
        });
    }

    @Override
    public Optional<ReceiptCounts> readMetricSnapshot(int maxRows, Duration timeout) {
        if (maxRows <= 0 || timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("memoryDlrSnapshot|error|reason:invalidBounds");
        }
        try {
            if (!owner.lock.tryLock(timeout.toNanos(), TimeUnit.NANOSECONDS)) {
                return Optional.empty();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
        try {
            owner.verifyAvailable();
            return receipts.size() > maxRows ? Optional.empty() :
                    Optional.of(new ReceiptCounts(waiting, pending, failed));
        } finally {
            owner.lock.unlock();
        }
    }

    private Optional<Receipt> live(Receipt receipt, long now) {
        if (receipt == null || receipt.expiry != null && receipt.expiry.at() <= now) {
            return Optional.empty();
        }
        if (receipt.state.getDeliveryStatus() == DeliveryStatus.WAITING_PROVIDER && receipt.expiry == null &&
                receipt.aliases.values().stream().noneMatch(correlation -> correlation.expiry.at() > now)) {
            return Optional.empty();
        }
        return Optional.of(receipt);
    }

    private void expireCorrelation(ProviderKey key, Correlation correlation) {
        Receipt receipt = correlation.receipt;
        unlinkCorrelation(key, correlation);
        if (receipt.aliases.isEmpty()) {
            removeReceipt(receipt);
        }
    }

    private void unlinkCorrelation(ProviderKey key, Correlation correlation) {
        correlations.remove(key, correlation);
        correlation.receipt.aliases.remove(key);
        expirations.remove(correlation.expiry);
    }

    private void clearCorrelations(Receipt receipt) {
        for (var alias : receipt.aliases.entrySet()) {
            correlations.remove(alias.getKey(), alias.getValue());
            expirations.remove(alias.getValue().expiry);
        }
        receipt.aliases.clear();
    }

    private void setExpiry(Receipt receipt, long at) {
        removeExpiry(receipt);
        receipt.expiry = new Expiry(at, ExpiryKind.RECEIPT, receipt.execution.id, "");
        expirations.add(receipt.expiry);
    }

    private void removeExpiry(Receipt receipt) {
        if (receipt.expiry != null) {
            expirations.remove(receipt.expiry);
            receipt.expiry = null;
        }
    }

    private void scheduleHttp(Receipt receipt) {
        if (receipt.state.getDeliveryChannel() != DeliveryChannel.HTTP) {
            return;
        }
        if (receipt.due != null) {
            httpDue.remove(receipt.due);
        }
        long at = receipt.state.getNextAttemptAt();
        receipt.due = new Due(receipt.claimedUntil == null ? at : Math.max(at, receipt.claimedUntil), receipt.execution.id);
        httpDue.add(receipt.due);
    }

    private void removeDeliveryIndex(Receipt receipt) {
        if (receipt.due != null) {
            httpDue.remove(receipt.due);
            receipt.due = null;
        }
        if (receipt.state.getDeliveryChannel() == DeliveryChannel.SMPP) {
            var indexed = smppPending.get(receipt.state.getSystemId());
            if (indexed != null) {
                indexed.remove(receipt);
                if (indexed.isEmpty()) {
                    smppPending.remove(receipt.state.getSystemId());
                }
            }
        }
    }

    private void removeReceipt(Receipt receipt) {
        if (receipt == null || !receipts.remove(receipt.execution.id, receipt)) {
            return;
        }
        clearCorrelations(receipt);
        removeExpiry(receipt);
        removeDeliveryIndex(receipt);
        switch (receipt.state.getDeliveryStatus()) {
            case WAITING_PROVIDER -> waiting--;
            case PENDING -> pending--;
            case FAILED -> failed--;
            default -> throw new DlrStorageException("memoryDlr|error|reason:unknownDeliveryStatus");
        }
        owner.releaseDlr(receipt.execution);
    }

    @Override
    public void close() {
        owner.lock.lock();
        try {
            receipts.clear();
            correlations.clear();
            expirations.clear();
            httpDue.clear();
            smppPending.clear();
            waiting = 0;
            pending = 0;
            failed = 0;
            owner.close();
        } finally {
            owner.lock.unlock();
        }
    }

    private <T> T locked(Supplier<T> operation) {
        owner.lock.lock();
        try {
            owner.verifyAvailable();
            try {
                return operation.get();
            } catch (DlrStorageException expected) {
                throw expected;
            } catch (RuntimeException | Error unexpected) {
                owner.fail(unexpected);
                throw unexpected;
            }
        } finally {
            owner.lock.unlock();
        }
    }

    private static MessageState prepare(MessageState state) {
        if (state == null) {
            throw new DlrStorageException("memoryDlr|error|reason:missingState");
        }
        String id = MemoryDlrReservations.gatewayId(state.getGatewayMsgId());
        if (state.getDeliveryChannel() == null || state.getDeliveryChannel() == DeliveryChannel.NONE ||
                state.getDlrMask() < 0 || state.getDlrMask() > 3 ||
                state.getDeliveryChannel() == DeliveryChannel.HTTP &&
                        (state.getForwardDlrUrl() == null || state.getForwardDlrUrl().isBlank()) ||
                state.getDeliveryChannel() == DeliveryChannel.SMPP &&
                        (state.getSystemId() == null || state.getSystemId().isBlank())) {
            throw new DlrStorageException("memoryDlr|error|reason:invalidDeliveryTarget");
        }
        var copy = new MessageState(id, state.getAccountId(), state.getSystemId(),
                state.getSourceAddr(), state.getDestAddr(), state.getForwardDlrUrl());
        copy.setDeliveryChannel(state.getDeliveryChannel());
        copy.setDlrMask(state.getDlrMask());
        copy.setHttpCallbackFormat(state.getHttpCallbackFormat());
        copy.setReassembledParts(state.getReassembledParts());
        return copy;
    }

    private static MessageState snapshot(MessageState state) {
        var copy = prepare(state);
        copy.setProviderName(state.getProviderName());
        copy.setProviderMessageId(state.getProviderMessageId());
        copy.setStatus(state.getStatus());
        copy.setDlrState(state.getDlrState());
        copy.setErrorCode(state.getErrorCode());
        copy.setDeliveryStatus(state.getDeliveryStatus());
        copy.setDeliveryAttemptCount(state.getDeliveryAttemptCount());
        copy.setLastAttemptAt(state.getLastAttemptAt());
        copy.setNextAttemptAt(state.getNextAttemptAt());
        copy.setLastDeliveryResult(state.getLastDeliveryResult());
        copy.setResolvedAt(state.getResolvedAt());
        copy.setTimestamp(state.getTimestamp());
        return copy;
    }

    private static ProviderKey providerKey(String provider, String id) {
        if (provider == null || provider.isBlank() || id == null || id.isBlank()) {
            throw new DlrStorageException("memoryDlr|error|reason:invalidCorrelation");
        }
        return new ProviderKey(provider, id);
    }

    private static long positiveMillis(Duration duration) {
        try {
            if (duration == null || duration.isNegative() || duration.toMillis() < 1) {
                throw new IllegalArgumentException("memoryDlrConfiguration|error|reason:nonPositiveDuration");
            }
            return duration.toMillis();
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("memoryDlrConfiguration|error|reason:durationOverflow", overflow);
        }
    }

    private static long deadline(long now, long duration) {
        try {
            return Math.addExact(now, duration);
        } catch (ArithmeticException overflow) {
            throw new DlrStorageException("memoryDlr|error|reason:deadlineOverflow", overflow);
        }
    }

    private static int batchBound(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("memoryDlr|error|reason:nonPositiveBatch");
        }
        return Math.min(limit, MAX_BATCH);
    }

    private static String normalize(String result) {
        return result == null || result.isBlank() ? null : result.strip();
    }

    private enum ExpiryKind { CORRELATION, RECEIPT }

    private record Expiry(long at, ExpiryKind kind, String first, String second) { }

    private record Due(long at, String id) { }

    private record Correlation(Receipt receipt, Expiry expiry) { }

    private static final class Receipt {
        private final Execution execution;
        private final long createdAt;
        private final Map<ProviderKey, Correlation> aliases = new HashMap<>();
        private MessageState state;
        private Expiry expiry;
        private Due due;
        private Long claimedUntil;

        private Receipt(Execution execution, MessageState state, long createdAt) {
            this.execution = execution;
            this.state = state;
            this.createdAt = createdAt;
        }
    }
}
