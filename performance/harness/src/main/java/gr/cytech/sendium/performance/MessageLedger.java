package gr.cytech.sendium.performance;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.EnumMap;
import java.util.function.LongSupplier;

/** All protocol callbacks share this bounded, synchronized ledger. */
public final class MessageLedger {
    public enum Admission { PENDING, ACCEPTED, REJECTED, FAILED, TIMED_OUT }
    public enum Event { PROVIDER_ARRIVAL, PROVIDER_RESPONSE, RECEIPT_GENERATED, RECEIPT_FORWARDED }
    public enum ReceiptResult { RECORDED, INVALID, BEYOND_RETENTION }
    public enum Latency { ACKNOWLEDGEMENT, SUBMISSION_TO_PROVIDER, PROVIDER_RESPONSE_COMPLETION,
        RECEIPT_FORWARDING, SIMULATOR_RECEIPT_DELAY }

    public record Counts(long scheduled, long attempted, long missed, long accepted,
            long rejected, long failed, long timedOut, long pendingAdmission,
            long providerArrivals, long providerResponses, long receiptsGenerated,
            long receiptsForwarded, long acceptedProviderCompleted, long acceptedAwaitingProvider,
            long acceptedReceiptForwarded, long acceptedAwaitingReceipt,
            long providerObservedWithoutAcceptance, long duplicateProviderRequests,
            long duplicateReceipts, long violations, long peakOutstanding, long peakTracked) { }

    private static final class Entry {
        final boolean measured;
        long dispatch;
        boolean explicitlyDispatched;
        Admission admission = Admission.PENDING;
        String ingressId;
        String forwardedIngressId;
        final EnumMap<Event, Long> events = new EnumMap<>(Event.class);
        Entry(boolean measured, long dispatch) { this.measured = measured; this.dispatch = dispatch; }
    }

    private record Retired(Entry entry, long retiredAt) { }
    private final RunConfig config;
    private final LongSupplier clock;
    private final String prefix;
    private final long retentionNanos;
    private final Map<String, Entry> active = new HashMap<>();
    private final Map<String, String> ingressIds = new HashMap<>();
    private final LinkedHashMap<String, Retired> retired = new LinkedHashMap<>();
    private final EnumMap<Latency, LatencyHistogram> histograms = new EnumMap<>(Latency.class);
    private long sequence, scheduled, attempted, missed, accepted, rejected, failed, timedOut;
    private long providerArrivals, providerResponses, receiptsGenerated, receiptsForwarded;
    private long providerCompleted, receiptCompleted, ambiguousProvider, duplicateProvider, duplicateReceipt;
    private long violations, outstanding, measuredPending, peakOutstanding, peakTracked, retiredEvictions;
    private long eventsBeyondRetention;
    private boolean frozen;

    public MessageLedger(RunConfig config, String runToken, LongSupplier clock, long retentionNanos) {
        if (!runToken.matches("[A-Za-z0-9-]{1,32}") || retentionNanos < 0) {
            throw new IllegalArgumentException("Invalid run token or retention");
        }
        this.config = config;
        this.prefix = runToken + ":";
        this.clock = clock;
        this.retentionNanos = retentionNanos;
        for (Latency latency : Latency.values()) { histograms.put(latency, new LatencyHistogram()); }
    }

    /** Null means a missed generation target. Never evict unfinished work. */
    public synchronized String attempt(boolean measured) {
        ensureOpen();
        prune();
        if (measured) { scheduled++; }
        if (active.size() >= config.maxTrackedMessages() || outstanding >= config.maxOutstandingRequests()) {
            if (measured) { missed++; }
            return null;
        }
        String token = prefix + Long.toUnsignedString(++sequence, 36);
        active.put(token, new Entry(measured, clock.getAsLong()));
        outstanding++;
        peakOutstanding = Math.max(peakOutstanding, outstanding);
        peakTracked = Math.max(peakTracked, active.size());
        if (measured) { attempted++; measuredPending++; }
        return token;
    }

    public synchronized void admission(String token, Admission outcome) {
        admission(token, outcome, null);
    }

    /** Exclude request construction from network latency; token allocation still reserves capacity. */
    public synchronized void markDispatched(String token) {
        ensureOpen();
        Entry entry = active.get(token);
        if (entry == null || entry.explicitlyDispatched || entry.admission != Admission.PENDING
                || !entry.events.isEmpty()) {
            violations++;
            throw new IllegalStateException("Invalid dispatch transition");
        }
        entry.dispatch = clock.getAsLong();
        entry.explicitlyDispatched = true;
    }

    public synchronized void admission(String token, Admission outcome, String ingressId) {
        ensureOpen();
        Entry entry = find(token);
        if (entry == null) { return; }
        if (outcome == Admission.PENDING || entry.admission != Admission.PENDING) { violations++; return; }
        entry.admission = outcome;
        entry.ingressId = ingressId;
        if (ingressId != null) {
            String previous = ingressIds.putIfAbsent(ingressId, token);
            if (previous != null && !previous.equals(token)) { violations++; }
        }
        if (ingressId != null && entry.forwardedIngressId != null
                && !ingressId.equals(entry.forwardedIngressId)) { violations++; }
        outstanding--;
        if (entry.measured) {
            measuredPending--;
            switch (outcome) {
                case ACCEPTED -> {
                    accepted++;
                    if (entry.events.containsKey(Event.PROVIDER_RESPONSE)) { providerCompleted++; }
                    if (entry.events.containsKey(Event.RECEIPT_FORWARDED)) { receiptCompleted++; }
                }
                case REJECTED -> rejected++;
                case FAILED -> failed++;
                case TIMED_OUT -> timedOut++;
                default -> throw new IllegalStateException();
            }
            if (outcome == Admission.ACCEPTED || outcome == Admission.REJECTED) {
                histograms.get(Latency.ACKNOWLEDGEMENT).recordNanos(clock.getAsLong() - entry.dispatch);
            }
            if ((outcome == Admission.FAILED || outcome == Admission.TIMED_OUT)
                    && entry.events.containsKey(Event.PROVIDER_ARRIVAL)) { ambiguousProvider++; }
        }
        if (outcome == Admission.REJECTED && !entry.events.isEmpty()) { violations++; }
        retireIfComplete(token, entry);
    }

    public synchronized void event(String token, Event event) {
        ensureOpen();
        Entry entry = find(token);
        if (entry == null) { return; }
        if (entry.events.containsKey(event)) {
            violations++;
            if (entry.measured && event == Event.PROVIDER_ARRIVAL) { duplicateProvider++; }
            if (entry.measured && event == Event.RECEIPT_FORWARDED) { duplicateReceipt++; }
            return;
        }
        if (entry.admission == Admission.REJECTED || !validOrder(entry, event)) { violations++; return; }
        long now = clock.getAsLong();
        entry.events.put(event, now);
        if (entry.measured) {
            switch (event) {
                case PROVIDER_ARRIVAL -> {
                    providerArrivals++;
                    sample(Latency.SUBMISSION_TO_PROVIDER, now - entry.dispatch);
                    if (entry.admission == Admission.FAILED || entry.admission == Admission.TIMED_OUT) { ambiguousProvider++; }
                }
                case PROVIDER_RESPONSE -> {
                    providerResponses++;
                    if (entry.admission == Admission.ACCEPTED) { providerCompleted++; }
                    sample(Latency.PROVIDER_RESPONSE_COMPLETION, now - entry.dispatch);
                }
                case RECEIPT_GENERATED -> {
                    receiptsGenerated++;
                    sample(Latency.SIMULATOR_RECEIPT_DELAY, now - entry.events.get(Event.PROVIDER_RESPONSE));
                }
                case RECEIPT_FORWARDED -> {
                    receiptsForwarded++;
                    if (entry.admission == Admission.ACCEPTED) { receiptCompleted++; }
                    sample(Latency.RECEIPT_FORWARDING, now - entry.events.get(Event.RECEIPT_GENERATED));
                }
            }
        }
        retireIfComplete(token, entry);
    }

    /** Validate and record provider arrival atomically before accepting a submit. */
    public synchronized boolean acceptProviderArrival(String token) {
        ensureOpen();
        prune();
        Entry entry = active.get(token);
        if (entry == null && retired.containsKey(token)) { entry = retired.get(token).entry(); }
        boolean valid = entry != null && entry.admission != Admission.REJECTED
                && !entry.events.containsKey(Event.PROVIDER_ARRIVAL);
        event(token, Event.PROVIDER_ARRIVAL);
        return valid;
    }

    /** Callback token permits correlation before the admission response supplies its gateway ID. */
    public synchronized boolean receipt(String token, String ingressId) {
        return receiveReceipt(token, ingressId) == ReceiptResult.RECORDED;
    }

    public synchronized ReceiptResult receiveReceipt(String token, String ingressId) {
        ensureOpen();
        long before = eventsBeyondRetention;
        Entry entry = find(token);
        if (entry == null) {
            return eventsBeyondRetention > before ? ReceiptResult.BEYOND_RETENTION : ReceiptResult.INVALID;
        }
        if (ingressId == null || ingressId.isBlank()
                || entry.ingressId != null && !entry.ingressId.equals(ingressId)
                || entry.forwardedIngressId != null && !entry.forwardedIngressId.equals(ingressId)) {
            violations++;
            return ReceiptResult.INVALID;
        }
        entry.forwardedIngressId = ingressId;
        boolean valid = validOrder(entry, Event.RECEIPT_FORWARDED) && entry.admission != Admission.REJECTED;
        event(token, Event.RECEIPT_FORWARDED);
        return valid ? ReceiptResult.RECORDED : ReceiptResult.INVALID;
    }

    private boolean validOrder(Entry entry, Event event) {
        return switch (event) {
            case PROVIDER_ARRIVAL -> true;
            case PROVIDER_RESPONSE -> entry.events.containsKey(Event.PROVIDER_ARRIVAL);
            case RECEIPT_GENERATED -> config.dlrEnabled() && entry.events.containsKey(Event.PROVIDER_RESPONSE);
            case RECEIPT_FORWARDED -> config.dlrEnabled() && entry.events.containsKey(Event.RECEIPT_GENERATED);
        };
    }

    private void sample(Latency latency, long nanos) { histograms.get(latency).recordNanos(nanos); }

    private Entry find(String token) {
        prune();
        Entry entry = active.get(token);
        if (entry == null) {
            Retired previous = retired.get(token);
            if (previous != null) { entry = previous.entry(); }
        }
        if (entry == null) {
            // An issued token outside bounded retention is not proof of an accounting violation.
            try {
                long issued = token != null && token.startsWith(prefix)
                        ? Long.parseUnsignedLong(token.substring(prefix.length()), 36) : 0;
                if (issued > 0 && issued <= sequence) { eventsBeyondRetention++; }
                else { violations++; }
            } catch (NumberFormatException exception) {
                violations++;
            }
        }
        return entry;
    }

    private void retireIfComplete(String token, Entry entry) {
        boolean complete = entry.admission == Admission.REJECTED
                || entry.admission == Admission.ACCEPTED && entry.events.containsKey(
                        config.dlrEnabled() ? Event.RECEIPT_FORWARDED : Event.PROVIDER_RESPONSE);
        if (complete && active.remove(token) != null) {
            retired.put(token, new Retired(entry, clock.getAsLong()));
            prune();
        }
    }

    private void prune() {
        var iterator = retired.entrySet().iterator();
        long now = clock.getAsLong();
        while (iterator.hasNext()) {
            var retiredEntry = iterator.next();
            Retired value = retiredEntry.getValue();
            if (retired.size() <= config.maxTrackedMessages() && now - value.retiredAt() < retentionNanos) { break; }
            iterator.remove();
            if (value.entry().ingressId != null) {
                ingressIds.remove(value.entry().ingressId, retiredEntry.getKey());
            }
            retiredEvictions++;
        }
    }

    public synchronized Counts snapshot() {
        return new Counts(scheduled, attempted, missed, accepted, rejected, failed, timedOut,
                measuredPending, providerArrivals, providerResponses, receiptsGenerated, receiptsForwarded,
                providerCompleted, accepted - providerCompleted, receiptCompleted,
                config.dlrEnabled() ? accepted - receiptCompleted : 0,
                ambiguousProvider, duplicateProvider, duplicateReceipt, violations, peakOutstanding, peakTracked);
    }

    public synchronized Map<Latency, LatencyHistogram.Snapshot> latencies() {
        Map<Latency, LatencyHistogram.Snapshot> result = new EnumMap<>(Latency.class);
        histograms.forEach((key, value) -> result.put(key, value.snapshot()));
        return Map.copyOf(result);
    }

    public synchronized boolean hasOutstandingWork() { return !active.isEmpty(); }
    public synchronized int activeSize() { return active.size(); }
    public synchronized int retiredSize() { prune(); return retired.size(); }
    public synchronized long retiredEvictions() { return retiredEvictions; }
    public synchronized long eventsBeyondRetention() { return eventsBeyondRetention; }
    public synchronized void freeze() { frozen = true; }
    private void ensureOpen() { if (frozen) { throw new IllegalStateException("Accounting is finalized"); } }
}
