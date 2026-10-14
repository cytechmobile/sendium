package gr.cytech.sendium.performance;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LatencyHistogramTest {
    @Test
    void emptySamplesAreNullAndOutOfRangeSamplesAreVisible() {
        LatencyHistogram histogram = new LatencyHistogram();
        assertNull(histogram.snapshot().p99Millis());
        histogram.recordNanos((LatencyHistogram.MAX_MICROS + 1) * 1000);
        assertEquals(1, histogram.snapshot().overflowCount());
        assertEquals(0, histogram.snapshot().count());
        assertThrows(IllegalArgumentException.class, () -> histogram.recordNanos(-1));
    }

    @Test
    void computesPercentilesWithoutKeepingIndividualSamples() {
        LatencyHistogram histogram = new LatencyHistogram();
        for (int millis = 1; millis <= 100; millis++) { histogram.recordNanos(millis * 1_000_000L); }
        var snapshot = histogram.snapshot();
        assertEquals(100, snapshot.count());
        assertEquals(50, snapshot.p50Millis(), 0.1);
        assertEquals(95, snapshot.p95Millis(), 0.1);
        assertEquals(99, snapshot.p99Millis(), 0.1);
    }
}
