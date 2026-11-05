package gr.cytech.sendium.performance;

import java.util.EnumMap;
import java.util.Map;

/** Mutable only under the owning ledger lock; shared update rules serve overall and cohorts. */
final class Measurements {
    enum Counter { SCHEDULED, ATTEMPTED, MISSED, ACCEPTED, REJECTED, FAILED, TIMED_OUT, PENDING,
        PROVIDER_ARRIVALS, PROVIDER_RESPONSES, RECEIPTS_GENERATED, RECEIPTS_FORWARDED,
        PROVIDER_COMPLETED, RECEIPT_COMPLETED, AMBIGUOUS_PROVIDER, DUPLICATE_PROVIDER, DUPLICATE_RECEIPT }
    private final long[] counters = new long[Counter.values().length];
    private final EnumMap<MessageLedger.Latency, LatencyHistogram> histograms = new EnumMap<>(MessageLedger.Latency.class);
    private long tracked, peakTracked, peakPending;

    void add(Counter counter, long amount) { counters[counter.ordinal()] = Math.addExact(get(counter), amount); }
    long get(Counter counter) { return counters[counter.ordinal()]; }
    void reserve() { tracked++; peakTracked = Math.max(peakTracked, tracked); peakPending = Math.max(peakPending, get(Counter.PENDING)); }
    void retire() { tracked--; }
    void sample(MessageLedger.Latency latency, long nanos) { histograms.computeIfAbsent(latency, ignored -> new LatencyHistogram()).recordNanos(nanos); }
    long providerPending() { return get(Counter.ACCEPTED) - get(Counter.PROVIDER_COMPLETED); }
    long receiptPending(boolean dlr) { return dlr ? get(Counter.ACCEPTED) - get(Counter.RECEIPT_COMPLETED) : 0; }

    MessageLedger.Counts snapshot(boolean dlr, long violations, long physicalPendingPeak, long physicalTrackedPeak) {
        return new MessageLedger.Counts(get(Counter.SCHEDULED), get(Counter.ATTEMPTED), get(Counter.MISSED),
                get(Counter.ACCEPTED), get(Counter.REJECTED), get(Counter.FAILED), get(Counter.TIMED_OUT), get(Counter.PENDING),
                get(Counter.PROVIDER_ARRIVALS), get(Counter.PROVIDER_RESPONSES), get(Counter.RECEIPTS_GENERATED),
                get(Counter.RECEIPTS_FORWARDED), get(Counter.PROVIDER_COMPLETED), providerPending(),
                get(Counter.RECEIPT_COMPLETED), receiptPending(dlr), get(Counter.AMBIGUOUS_PROVIDER),
                get(Counter.DUPLICATE_PROVIDER), get(Counter.DUPLICATE_RECEIPT), violations,
                physicalPendingPeak, physicalTrackedPeak);
    }

    MessageLedger.Counts snapshot(boolean dlr) { return snapshot(dlr, 0, peakPending, peakTracked); }

    Map<MessageLedger.Latency, LatencyHistogram.Snapshot> latencies() {
        var result = new EnumMap<MessageLedger.Latency, LatencyHistogram.Snapshot>(MessageLedger.Latency.class);
        for (var latency : MessageLedger.Latency.values()) {
            LatencyHistogram histogram = histograms.get(latency);
            result.put(latency, histogram == null ? new LatencyHistogram.Snapshot(0, null, null, null, null, null, 0) : histogram.snapshot());
        }
        return Map.copyOf(result);
    }
}
