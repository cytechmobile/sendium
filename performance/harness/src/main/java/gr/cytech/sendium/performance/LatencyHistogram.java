package gr.cytech.sendium.performance;

import org.HdrHistogram.Histogram;

/** Fixed range and precision; samples are microseconds and output is milliseconds. */
public final class LatencyHistogram {
    public static final long MAX_MICROS = 3_600_000_000L;
    private final Histogram histogram = new Histogram(1, MAX_MICROS, 3);
    private long overflowCount;

    public record Snapshot(long count, Double p50Millis, Double p95Millis,
            Double p99Millis, Double minMillis, Double maxMillis, long overflowCount) { }

    public synchronized void recordNanos(long nanos) {
        if (nanos < 0) {
            throw new IllegalArgumentException("Negative latency");
        }
        long micros = nanos / 1000;
        if (micros > MAX_MICROS) {
            overflowCount++;
        } else {
            histogram.recordValue(micros);
        }
    }

    public synchronized Snapshot snapshot() {
        long count = histogram.getTotalCount();
        if (count == 0) {
            return new Snapshot(0, null, null, null, null, null, overflowCount);
        }
        return new Snapshot(count, histogram.getValueAtPercentile(50) / 1000.0,
                histogram.getValueAtPercentile(95) / 1000.0,
                histogram.getValueAtPercentile(99) / 1000.0,
                histogram.getMinValue() / 1000.0, histogram.getMaxValue() / 1000.0, overflowCount);
    }
}
