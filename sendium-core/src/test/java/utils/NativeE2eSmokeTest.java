package utils;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NativeE2eSmokeTest {

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
