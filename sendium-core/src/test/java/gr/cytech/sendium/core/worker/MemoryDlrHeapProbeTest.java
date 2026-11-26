package gr.cytech.sendium.core.worker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryDlrHeapProbeTest {
    @Test
    void readsResidentKibibytesAsBytes() {
        assertThat(MemoryDlrHeapProbe.residentBytes(List.of("Name: java", "VmRSS:\t1234 kB")))
                .isEqualTo(1234L * 1024);
    }

    @Test
    void missingResidentMemoryIsUnavailable() {
        assertThat(MemoryDlrHeapProbe.residentBytes(List.of("Name: java"))).isEqualTo(-1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "invalid kB", "-1 kB", "9223372036854775808 kB", "9223372036854775807 kB"})
    void malformedResidentMemoryIsUnavailable(String value) {
        assertThat(MemoryDlrHeapProbe.residentBytes(List.of("VmRSS: " + value))).isEqualTo(-1);
    }
}
