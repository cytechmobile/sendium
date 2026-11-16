package utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NativeE2eSmokeTest {

    @Test
    void absentPortOverrideUsesTheDefault() {
        assertThat(NativeE2eSmoke.smppPort(null)).isEqualTo(27777);
    }

    @ParameterizedTest
    @CsvSource({"1, 1", "28777, 28777", "65535, 65535"})
    void acceptsValidPortOverrides(String value, int expected) {
        assertThat(NativeE2eSmoke.smppPort(value)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "invalid", "1.5", " 28777 ", "0", "-1", "65536", "2147483648"})
    void rejectsMalformedAndOutOfRangePortsWithoutFallback(String value) {
        assertThatThrownBy(() -> NativeE2eSmoke.smppPort(value))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("SENDIUM_E2E_SMPP_PORT must be an integer between 1 and 65535")
                .hasNoCause();
    }

    @Test
    void hasSuccessfulStorageOperationIgnoresMalformedMetricValues() {
        String metrics = """
                sendium_dlr_storage_operation_seconds_count{backend="postgresql",operation="provider_batch",outcome="success"} invalid
                sendium_dlr_storage_operation_seconds_count{backend="postgresql",operation="provider_batch",outcome="success"} 1.0
                """;

        assertThat(NativeE2eSmoke.hasSuccessfulStorageOperation(metrics, "provider_batch", 0)).isTrue();
    }

    @Test
    void hasSuccessfulStorageOperationRejectsOnlyMalformedMetricValues() {
        String metrics = """
                sendium_dlr_storage_operation_seconds_count{backend="postgresql",operation="provider_batch",outcome="success"} invalid
                """;

        assertThat(NativeE2eSmoke.hasSuccessfulStorageOperation(metrics, "provider_batch", 0)).isFalse();
    }

    @Test
    void requiresAnotherCommittedBatchInsteadOfAcceptingAnExistingCount() {
        String metrics = """
                sendium_dlr_storage_operation_seconds_count{backend="postgresql",operation="provider_batch",outcome="success"} 3.0
                """;

        assertThat(NativeE2eSmoke.successfulStorageOperationCount(metrics, "provider_batch")).isEqualTo(3);
        assertThat(NativeE2eSmoke.hasSuccessfulStorageOperation(metrics, "provider_batch", 3)).isFalse();
        assertThat(NativeE2eSmoke.hasSuccessfulStorageOperation(metrics, "provider_batch", 2)).isTrue();
    }

    @Test
    void failedBatchesAndUnrelatedOperationsCannotSatisfyTheRestartGate() {
        String metrics = """
                sendium_dlr_storage_operation_seconds_count{backend="postgresql",operation="provider_batch",outcome="success"} 0.0
                sendium_dlr_storage_operation_seconds_count{backend="postgresql",operation="provider_batch",outcome="error"} 5.0
                sendium_dlr_storage_operation_seconds_count{backend="postgresql",operation="record_provider_accepted",outcome="success"} 7.0
                sendium_dlr_storage_operation_seconds_count{backend="other",operation="provider_batch",outcome="success"} 9.0
                """;

        assertThat(NativeE2eSmoke.hasSuccessfulStorageOperation(metrics, "provider_batch", 0)).isFalse();
    }

    @Test
    void nonFiniteValuesCannotProveACommit() {
        String metrics = """
                sendium_dlr_storage_operation_seconds_count{backend="postgresql",operation="provider_batch",outcome="success"} NaN
                sendium_dlr_storage_operation_seconds_count{backend="postgresql",operation="provider_batch",outcome="success"} Infinity
                """;

        assertThat(NativeE2eSmoke.hasSuccessfulStorageOperation(metrics, "provider_batch", 0)).isFalse();
    }
}
