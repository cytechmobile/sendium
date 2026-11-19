package utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NativeE2eSmokeTest {

    @Test
    void storageProfileDefaultsPreserveExistingSmokeModes() {
        assertThat(NativeE2eSmoke.storageProfile(null, false)).isEqualTo("memory/memory/memory");
        assertThat(NativeE2eSmoke.storageProfile(null, true)).isEqualTo("file/memory/memory");
    }

    @ParameterizedTest
    @ValueSource(strings = {"file/memory/memory", "file/file/memory", "file/memory/file", "file/file/file"})
    void acceptsEachRetainedVolumeProfile(String profile) {
        assertThat(NativeE2eSmoke.storageProfile(profile, true)).isEqualTo(profile);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "file/file/postgresql", "memory/memory/file", "file|secret\n"})
    void rejectsUnsupportedSmokeProfilesWithoutEchoingTheInput(String profile) {
        assertThatThrownBy(() -> NativeE2eSmoke.storageProfile(profile, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("SENDIUM_E2E_STORAGE_PROFILE must name a supported outbound storage profile");
    }

    @Test
    void retainedVolumeModeCannotClaimMemoryDurability() {
        assertThatThrownBy(() -> NativeE2eSmoke.storageProfile("memory/memory/memory", true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("SENDIUM_E2E_PENDING_RESTART requires file pending storage");
    }

    @Test
    void fileProfileCannotSilentlyRunTheMemorySmoke() {
        assertThatThrownBy(() -> NativeE2eSmoke.storageProfile("file/file/file", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("File smoke profiles require SENDIUM_E2E_PENDING_RESTART=true");
    }

    @Test
    void dlrBackendDefaultsToThePostgresqlSmoke() {
        assertThat(NativeE2eSmoke.dlrBackend(null, false)).isEqualTo("postgresql");
        assertThat(NativeE2eSmoke.dlrBackend(null, true)).isEqualTo("postgresql");
        assertThat(NativeE2eSmoke.dlrBackend("file", false)).isEqualTo("file");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "memory", "FILE", "file|secret\n"})
    void rejectsUnsupportedDlrBackendsWithoutEchoingTheInput(String backend) {
        assertThatThrownBy(() -> NativeE2eSmoke.dlrBackend(backend, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("SENDIUM_E2E_DLR_BACKEND must be postgresql or file");
    }

    @Test
    void fileDlrScenariosDoNotCombineWithPendingRestart() {
        assertThatThrownBy(() -> NativeE2eSmoke.dlrBackend("file", true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("File DLR smoke runs its own restart scenarios; unset SENDIUM_E2E_PENDING_RESTART");
    }

    @Test
    void storageOperationCountsAreScopedToTheSelectedBackend() {
        String metrics = """
                sendium_dlr_storage_operation_seconds_count{backend="postgresql",operation="provider_batch",outcome="success"} 5.0
                sendium_dlr_storage_operation_seconds_count{backend="file",operation="provider_batch",outcome="success"} 2.0
                """;
        assertThat(NativeE2eSmoke.successfulStorageOperationCount(metrics, "file", "provider_batch")).isEqualTo(2);
        assertThat(NativeE2eSmoke.successfulStorageOperationCount(metrics, "postgresql", "provider_batch")).isEqualTo(5);
    }

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
