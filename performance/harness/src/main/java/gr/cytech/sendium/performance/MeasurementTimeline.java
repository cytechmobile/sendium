package gr.cytech.sendium.performance;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import static gr.cytech.sendium.performance.Measurements.Counter.*;

/** Fixed-count minute buckets; no per-message event history. Owned by the ledger lock. */
public final class MeasurementTimeline {
    public static final int DEFAULT_MAX_INTERVALS = 256;
    private static final long MINUTE = TimeUnit.MINUTES.toNanos(1);
    public record Backlog(long pendingAdmission, long acceptedAwaitingProvider, long acceptedAwaitingReceipt) { }
    public record Cohort(long startOffsetNanos, long endOffsetNanos, long observedNanos,
            MessageLedger.Counts counts, Map<MessageLedger.Latency, LatencyHistogram.Snapshot> latencies) { }
    public record EventInterval(String phase, long startOffsetNanos, long durationNanos,
            long providerArrivals, long providerResponses, long receiptsGenerated, long receiptsForwarded, Backlog outstanding) { }
    public record Snapshot(long durationNanos, long drainNanos, List<Cohort> cohorts,
            List<EventInterval> events, long outsideWindowEvents, int intervalBudget) { }

    private static final class Events {
        final long[] counts = new long[MessageLedger.Event.values().length];
        final long[] backlogChanges = new long[3];
    }
    private final long start, plannedDuration, maxDrain;
    private final int budget;
    private final Measurements[] cohorts;
    private final Events[] measurementEvents, drainEvents;
    private Long endOffset, finishOffset;
    private long outsideWindowEvents;
    private long lastMeasurementUpdate = -1, lastDrainUpdate = -1;

    MeasurementTimeline(long start, long duration, long maxDrain, int budget) {
        if (duration <= 0 || maxDrain < 0 || budget < 1 || buckets(duration) + buckets(maxDrain) > budget) {
            throw new IllegalArgumentException("Measurement exceeds configured interval memory budget");
        }
        this.start = start; plannedDuration = duration; this.maxDrain = maxDrain; this.budget = budget;
        cohorts = new Measurements[(int) buckets(duration)];
        measurementEvents = new Events[cohorts.length];
        drainEvents = new Events[(int) buckets(maxDrain)];
        for (int i = 0; i < cohorts.length; i++) { cohorts[i] = new Measurements(); measurementEvents[i] = new Events(); }
        for (int i = 0; i < drainEvents.length; i++) { drainEvents[i] = new Events(); }
    }

    private static long buckets(long nanos) { return nanos / MINUTE + (nanos % MINUTE == 0 ? 0 : 1); }
    boolean matches(long origin, long duration) { return start == origin && plannedDuration == duration; }
    Measurements cohort(long scheduled) {
        long offset = scheduled - start;
        if (offset < 0 || offset >= plannedDuration) { throw new IllegalArgumentException("Measured target is outside its cohort window"); }
        return cohorts[(int) (offset / MINUTE)];
    }

    void missedRange(long phaseStart, long first, long count, double rate) {
        long limit = Math.addExact(first, count);
        double period = 1_000_000_000.0 / rate;
        while (first < limit) {
            long scheduled = phaseStart + (long) Math.ceil(first * period);
            Measurements bucket = cohort(scheduled);
            long boundary = Math.min(plannedDuration, ((scheduled - start) / MINUTE + 1) * MINUTE);
            long low = first, high = limit;
            while (low < high) {
                long middle = low + (high - low) / 2;
                if (phaseStart + (long) Math.ceil(middle * period) - start < boundary) { low = middle + 1; }
                else { high = middle; }
            }
            long amount = low - first;
            if (amount == 0) { throw new IllegalArgumentException("Invalid missed-target range"); }
            bucket.add(SCHEDULED, amount); bucket.add(MISSED, amount);
            first = low;
        }
    }

    void recordEvent(long when, MessageLedger.Event event) {
        Events bucket = eventsAt(when);
        if (bucket != null) { bucket.counts[event.ordinal()]++; }
    }

    void backlog(long when, long pending, long provider, long receipt) {
        if (pending == 0 && provider == 0 && receipt == 0) { return; }
        Events bucket = eventsAt(when);
        if (bucket != null) {
            bucket.backlogChanges[0] += pending;
            bucket.backlogChanges[1] += provider;
            bucket.backlogChanges[2] += receipt;
        }
    }

    private Events eventsAt(long when) {
        long offset = when - start;
        long end = endOffset == null ? plannedDuration : endOffset;
        if (offset < 0 || offset >= end + maxDrain || finishOffset != null && offset >= finishOffset) { outsideWindowEvents++; return null; }
        if (offset < end) {
            lastMeasurementUpdate = Math.max(lastMeasurementUpdate, offset);
            return measurementEvents[(int) (offset / MINUTE)];
        }
        lastDrainUpdate = Math.max(lastDrainUpdate, offset);
        return drainEvents[(int) ((offset - end) / MINUTE)];
    }

    long end(long now) {
        if (endOffset == null) { endOffset = observedEnd(now); }
        return start + endOffset;
    }
    void finish(long now) {
        end(now);
        if (finishOffset == null) { finishOffset = observedFinish(now, endOffset); }
    }

    Snapshot snapshot(boolean dlr, long now) {
        long end = endOffset == null ? observedEnd(now) : endOffset;
        long finish = finishOffset == null ? observedFinish(now, end) : finishOffset;
        List<Cohort> cohortSnapshots = new ArrayList<>();
        for (int i = 0; i < cohorts.length; i++) {
            long from = i * MINUTE, to = Math.min(plannedDuration, from + MINUTE);
            cohortSnapshots.add(new Cohort(from, to, Math.max(0, Math.min(to, end) - from),
                    cohorts[i].snapshot(dlr), cohorts[i].latencies()));
        }
        List<EventInterval> events = new ArrayList<>();
        long[] backlog = new long[3];
        append(events, "measurement", measurementEvents, 0, end, backlog);
        append(events, "drain", drainEvents, end, finish - end, backlog);
        return new Snapshot(end, finish - end, List.copyOf(cohortSnapshots), List.copyOf(events), outsideWindowEvents, budget);
    }

    // Closing a half-open observation after a same-tick callback must include that callback.
    // Absolute configured deadlines remain exclusive; this only advances early close by one tick.
    private long observedEnd(long now) { return Math.max(0, Math.min(plannedDuration, Math.max(now - start, lastMeasurementUpdate + 1))); }
    private long observedFinish(long now, long end) { return Math.max(end, Math.min(end + maxDrain, Math.max(now - start, lastDrainUpdate + 1))); }

    private static void append(List<EventInterval> result, String phase, Events[] buckets, long base, long duration, long[] backlog) {
        for (int i = 0; i < buckets.length && i * MINUTE < duration; i++) {
            Events bucket = buckets[i];
            for (int j = 0; j < backlog.length; j++) { backlog[j] += bucket.backlogChanges[j]; }
            result.add(new EventInterval(phase, base + i * MINUTE, Math.min(MINUTE, duration - i * MINUTE),
                    bucket.counts[MessageLedger.Event.PROVIDER_ARRIVAL.ordinal()],
                    bucket.counts[MessageLedger.Event.PROVIDER_RESPONSE.ordinal()],
                    bucket.counts[MessageLedger.Event.RECEIPT_GENERATED.ordinal()],
                    bucket.counts[MessageLedger.Event.RECEIPT_FORWARDED.ordinal()], new Backlog(backlog[0], backlog[1], backlog[2])));
        }
    }
}
