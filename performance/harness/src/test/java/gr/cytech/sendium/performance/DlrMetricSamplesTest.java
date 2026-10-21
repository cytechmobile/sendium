package gr.cytech.sendium.performance;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DlrMetricSamplesTest {
    @Test
    void readsIntegerAndFractionalMetricValues() {
        assertEquals(5.0, DlrMetricSamples.parseValue("sendium_dlr_storage_operation_seconds_count 5"));
        assertEquals(5.0, DlrMetricSamples.parseValue("sendium_dlr_storage_operation_seconds_count{outcome=\"success\"} 5.0"));
        assertEquals(0.25, DlrMetricSamples.parseValue("sendium_dlr_storage_operation_seconds_sum 0.25"));
    }

    @Test
    void malformedSampleFailsWithItsContextAndOriginalCause() {
        String sample = "sendium_dlr_storage_operation_seconds_count{outcome=\"error\"} malformed";
        AssertionError failure = assertThrows(AssertionError.class, () -> DlrMetricSamples.parseValue(sample));
        assertTrue(failure.getMessage().contains(sample));
        assertInstanceOf(NumberFormatException.class, failure.getCause());
    }
}
