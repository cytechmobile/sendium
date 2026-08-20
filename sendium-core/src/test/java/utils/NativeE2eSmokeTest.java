package utils;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NativeE2eSmokeTest {

    @Test
    void hasSuccessfulStorageOperationIgnoresMalformedMetricValues() {
        String metrics = """
                sendium_dlr_storage_operation_seconds_count{backend="postgresql",operation="link_provider",outcome="success"} invalid
                sendium_dlr_storage_operation_seconds_count{backend="postgresql",operation="link_provider",outcome="success"} 1.0
                """;

        assertThat(NativeE2eSmoke.hasSuccessfulStorageOperation(metrics, "link_provider")).isTrue();
    }

    @Test
    void hasSuccessfulStorageOperationRejectsOnlyMalformedMetricValues() {
        String metrics = """
                sendium_dlr_storage_operation_seconds_count{backend="postgresql",operation="link_provider",outcome="success"} invalid
                """;

        assertThat(NativeE2eSmoke.hasSuccessfulStorageOperation(metrics, "link_provider")).isFalse();
    }
}
