package gr.cytech.sendium.performance;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.EnumMap;
import java.util.function.LongSupplier;
import java.util.concurrent.TimeUnit;
import static gr.cytech.sendium.performance.Measurements.Counter.*;

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
        final long scheduledNanos;
        final Measurements cohort;
        long dispatch;
        long admittedAt;
        boolean explicitlyDispatched;
        Admission admission = Admission.PENDING;
        String ingressId;
        String forwardedIngressId;
        final EnumMap<Event, Long> events = new EnumMap<>(Event.class);
        Entry(boolean measured, long scheduledNanos, long dispatch, Measurements cohort) {
            this.measured = measured; this.scheduledNanos = scheduledNanos; this.dispatch = dispatch; this.cohort = cohort;
        }
    }

    private record Retired(Entry entry, long retiredAt) { }
    private final RunConfig config;
    private final LongSupplier clock;
    private final String prefix;
    private final long retentionNanos;
    private final Map<String, Entry> active = new HashMap<>();
    private final Map<String, String> ingressIds = new HashMap<>();
    private final LinkedHashMap<String, Retired> retired = new LinkedHashMap<>();
    private final Measurements overall = new Measurements();
    private MeasurementTimeline timeline;
    private long sequence, violations, outstanding, peakOutstanding, peakTracked, retiredEvictions;
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
    }

    public synchronized void beginMeasurement(long start, long duration) {
        beginMeasurement(start, duration, MeasurementTimeline.DEFAULT_MAX_INTERVALS);
    }

    public synchronized void beginMeasurement(long start, long duration, int intervalBudget) {
        ensureOpen();
        if (duration == 0) { return; }
        if (timeline != null) {
            if (!timeline.matches(start, duration)) { throw new IllegalStateException("Measurement window already defined"); }
            return;
        }
        if (overall.get(SCHEDULED) != 0) { throw new IllegalStateException("Define the window before measurement traffic"); }
        timeline = new MeasurementTimeline(start, duration, TimeUnit.SECONDS.toNanos(config.drainTimeoutSeconds()), intervalBudget);
    }

    public synchronized long endMeasurement() { return timeline == null ? clock.getAsLong() : timeline.end(clock.getAsLong()); }
    public synchronized void finishDrain() { if (timeline != null) { timeline.finish(clock.getAsLong()); } }
    public synchronized MeasurementTimeline.Snapshot timeline() { return timeline == null ? null : timeline.snapshot(config.dlrEnabled(), clock.getAsLong()); }

    /** Null means a missed generation target. Never evict unfinished work. */
    public synchronized String attempt(boolean measured) {
        return attempt(measured, clock.getAsLong());
    }

    public synchronized String attempt(boolean measured, long scheduledNanos) {
        ensureOpen();
        prune();
        Measurements cohort = measured && timeline != null ? timeline.cohort(scheduledNanos) : null;
        Entry entry = new Entry(measured, scheduledNanos, clock.getAsLong(), cohort);
        add(entry, SCHEDULED, 1);
        if (active.size() >= config.maxTrackedMessages() || outstanding >= config.maxOutstandingRequests()) {
            add(entry, MISSED, 1);
            return null;
        }
        String token = prefix + Long.toUnsignedString(++sequence, 36);
        active.put(token, entry);
        outstanding++;
        peakOutstanding = Math.max(peakOutstanding, outstanding);
        peakTracked = Math.max(peakTracked, active.size());
        add(entry, ATTEMPTED, 1); add(entry, PENDING, 1);
        if (cohort != null) { cohort.reserve(); }
        if (measured && timeline != null) { timeline.backlog(clock.getAsLong(), 1, 0, 0); }
        return token;
    }

    /** Account for skipped scheduler targets in constant space, including a late phase start/stop. */
    public synchronized void missedTargets(boolean measured, long count) {
        ensureOpen();
        if (count < 0) { throw new IllegalArgumentException("Negative target count"); }
        if (measured) {
            if (timeline != null) { throw new IllegalStateException("Interval accounting requires timestamped missed targets"); }
            overall.add(SCHEDULED, count); overall.add(MISSED, count);
        }
    }

    synchronized void missedTargets(boolean measured, long phaseStart, long first, long count, double rate) {
        ensureOpen();
        if (count < 0) { throw new IllegalArgumentException("Negative target count"); }
        if (measured) {
            if (timeline != null) { timeline.missedRange(phaseStart, first, count, rate); }
            overall.add(SCHEDULED, count); overall.add(MISSED, count);
        }
    }

    public synchronized Long scheduledNanos(String token) {
        prune();
        Entry entry = active.get(token);
        if (entry == null && retired.containsKey(token)) { entry = retired.get(token).entry(); }
        return entry == null ? null : entry.scheduledNanos;
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
        if (outcome == null || outcome == Admission.PENDING || entry.admission != Admission.PENDING) { violations++; return; }
        long admittedAt = clock.getAsLong();
        if (admittedAt - entry.dispatch < 0) { violations++; return; }
        entry.admission = outcome;
        entry.admittedAt = admittedAt;
        entry.ingressId = ingressId;
        if (ingressId != null) {
            String previous = ingressIds.putIfAbsent(ingressId, token);
            if (previous != null && !previous.equals(token)) { violations++; }
        }
        if (ingressId != null && entry.forwardedIngressId != null
                && !ingressId.equals(entry.forwardedIngressId)) { violations++; }
        outstanding--;
        if (entry.measured) {
            long providerBefore = overall.providerPending(), receiptBefore = overall.receiptPending(config.dlrEnabled());
            add(entry, PENDING, -1);
            switch (outcome) {
                case ACCEPTED -> {
                    add(entry, ACCEPTED, 1);
                    if (entry.events.containsKey(Event.PROVIDER_RESPONSE)) { add(entry, PROVIDER_COMPLETED, 1); }
                    if (entry.events.containsKey(Event.RECEIPT_FORWARDED)) { add(entry, RECEIPT_COMPLETED, 1); }
                }
                case REJECTED -> add(entry, REJECTED, 1);
                case FAILED -> add(entry, FAILED, 1);
                case TIMED_OUT -> add(entry, TIMED_OUT, 1);
                default -> throw new IllegalStateException();
            }
            if (outcome == Admission.ACCEPTED || outcome == Admission.REJECTED) {
                sample(entry, Latency.ACKNOWLEDGEMENT, entry.admittedAt - entry.dispatch);
            }
            if ((outcome == Admission.FAILED || outcome == Admission.TIMED_OUT)
                    && entry.events.containsKey(Event.PROVIDER_ARRIVAL)) { add(entry, AMBIGUOUS_PROVIDER, 1); }
            if (timeline != null) { timeline.backlog(entry.admittedAt, -1,
                    overall.providerPending() - providerBefore, overall.receiptPending(config.dlrEnabled()) - receiptBefore); }
        }
        if (outcome == Admission.REJECTED && !entry.events.isEmpty()) { violations++; }
        retireIfComplete(token, entry);
    }

    public synchronized void event(String token, Event event) {
        eventAt(token, event, clock.getAsLong());
    }

    private boolean eventAt(String token, Event event, long now) {
        ensureOpen();
        Entry entry = find(token);
        return entry != null && eventAt(token, entry, event, now);
    }

    private boolean eventAt(String token, Entry entry, Event event, long now) {
        if (!validOrder(entry, event)) { violations++; return false; }
        long predecessor = switch (event) {
            case PROVIDER_ARRIVAL -> entry.dispatch;
            case PROVIDER_RESPONSE -> entry.events.get(Event.PROVIDER_ARRIVAL);
            case RECEIPT_GENERATED -> entry.events.get(Event.PROVIDER_RESPONSE);
            case RECEIPT_FORWARDED -> entry.events.get(Event.RECEIPT_GENERATED);
        };
        if (now - predecessor < 0 || now - clock.getAsLong() > 0) { violations++; return false; }
        if (entry.events.containsKey(event)) {
            violations++;
            if (event == Event.PROVIDER_ARRIVAL) { add(entry, DUPLICATE_PROVIDER, 1); }
            if (event == Event.RECEIPT_FORWARDED) { add(entry, DUPLICATE_RECEIPT, 1); }
            return event == Event.RECEIPT_FORWARDED;
        }
        if (entry.admission == Admission.REJECTED) { violations++; return false; }
        entry.events.put(event, now);
        if (entry.measured) {
            long providerBefore = overall.providerPending(), receiptBefore = overall.receiptPending(config.dlrEnabled());
            switch (event) {
                case PROVIDER_ARRIVAL -> {
                    add(entry, PROVIDER_ARRIVALS, 1);
                    sample(entry, Latency.SUBMISSION_TO_PROVIDER, now - entry.dispatch);
                    if (entry.admission == Admission.FAILED || entry.admission == Admission.TIMED_OUT) { add(entry, AMBIGUOUS_PROVIDER, 1); }
                }
                case PROVIDER_RESPONSE -> {
                    add(entry, PROVIDER_RESPONSES, 1);
                    if (entry.admission == Admission.ACCEPTED) { add(entry, PROVIDER_COMPLETED, 1); }
                    sample(entry, Latency.PROVIDER_RESPONSE_COMPLETION, now - entry.dispatch);
                }
                case RECEIPT_GENERATED -> {
                    add(entry, RECEIPTS_GENERATED, 1);
                    sample(entry, Latency.SIMULATOR_RECEIPT_DELAY, now - entry.events.get(Event.PROVIDER_RESPONSE));
                }
                case RECEIPT_FORWARDED -> {
                    add(entry, RECEIPTS_FORWARDED, 1);
                    if (entry.admission == Admission.ACCEPTED) { add(entry, RECEIPT_COMPLETED, 1); }
                    sample(entry, Latency.RECEIPT_FORWARDING, now - entry.events.get(Event.RECEIPT_GENERATED));
                }
            }
            if (timeline != null) {
                timeline.recordEvent(now, event);
                long backlogTime = entry.admission == Admission.ACCEPTED && now - entry.admittedAt < 0 ? entry.admittedAt : now;
                timeline.backlog(backlogTime, 0, overall.providerPending() - providerBefore,
                        overall.receiptPending(config.dlrEnabled()) - receiptBefore);
            }
        }
        retireIfComplete(token, entry);
        return true;
    }

    /** Validate and record provider arrival atomically before accepting a submit. */
    public synchronized boolean acceptProviderArrival(String token) {
        ensureOpen();
        Entry entry = find(token);
        return entry != null && eventAt(token, entry, Event.PROVIDER_ARRIVAL, clock.getAsLong());
    }

    /** Callback token permits correlation before the admission response supplies its gateway ID. */
    public synchronized boolean receipt(String token, String ingressId) {
        return receiveReceipt(token, ingressId) == ReceiptResult.RECORDED;
    }

    public synchronized ReceiptResult receiveReceipt(String token, String ingressId) {
        return receiveReceiptAt(token, ingressId, clock.getAsLong());
    }

    public synchronized String tokenForIngressId(String ingressId) {
        prune();
        return ingressIds.get(ingressId);
    }

    public synchronized ReceiptResult receiveReceiptAt(String token, String ingressId, long arrivalNanos) {
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
        if (!eventAt(token, entry, Event.RECEIPT_FORWARDED, arrivalNanos)) { return ReceiptResult.INVALID; }
        entry.forwardedIngressId = ingressId;
        return ReceiptResult.RECORDED;
    }

    private boolean validOrder(Entry entry, Event event) {
        return switch (event) {
            case PROVIDER_ARRIVAL -> true;
            case PROVIDER_RESPONSE -> entry.events.containsKey(Event.PROVIDER_ARRIVAL);
            case RECEIPT_GENERATED -> config.dlrEnabled() && entry.events.containsKey(Event.PROVIDER_RESPONSE);
            case RECEIPT_FORWARDED -> config.dlrEnabled() && entry.events.containsKey(Event.RECEIPT_GENERATED);
        };
    }

    private void add(Entry entry, Measurements.Counter counter, long amount) {
        if (entry.measured) { overall.add(counter, amount); if (entry.cohort != null) { entry.cohort.add(counter, amount); } }
    }
    private void sample(Entry entry, Latency latency, long nanos) {
        overall.sample(latency, nanos);
        if (entry.cohort != null) { entry.cohort.sample(latency, nanos); }
    }

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
                if (issued > 0 && issued <= sequence && token.equals(prefix + Long.toUnsignedString(issued, 36))) { eventsBeyondRetention++; }
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
            if (entry.cohort != null) { entry.cohort.retire(); }
            retired.put(token, new Retired(entry, clock.getAsLong()));
            prune();
        }
    }

    private void prune() {
        if (frozen) { return; }
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
        return overall.snapshot(config.dlrEnabled(), violations, peakOutstanding, peakTracked);
    }

    public synchronized Map<Latency, LatencyHistogram.Snapshot> latencies() {
        return overall.latencies();
    }

    public synchronized boolean hasOutstandingWork() { return !active.isEmpty(); }
    public synchronized int activeSize() { return active.size(); }
    public synchronized int retiredSize() { prune(); return retired.size(); }
    public synchronized long retiredEvictions() { return retiredEvictions; }
    public synchronized long eventsBeyondRetention() { return eventsBeyondRetention; }
    public synchronized long outstandingRequests() { return outstanding; }
    public synchronized long retentionNanos() { return retentionNanos; }
    public synchronized boolean isFrozen() { return frozen; }
    public synchronized void freeze() { prune(); if (timeline != null) { timeline.finish(clock.getAsLong()); } frozen = true; }
    private void ensureOpen() { if (frozen) { throw new IllegalStateException("Accounting is finalized"); } }
}
