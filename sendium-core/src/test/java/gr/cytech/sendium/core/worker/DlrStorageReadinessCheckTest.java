package gr.cytech.sendium.core.worker;

import org.eclipse.microprofile.health.HealthCheckResponse;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DlrStorageReadinessCheckTest {
    @Test
    void reportsMvStoreMode() {
        ConfiguredDlrStorage storage = mock(ConfiguredDlrStorage.class);
        when(storage.backend()).thenReturn("mvstore");
        when(storage.mode()).thenReturn("memory");
        DlrStorageReadinessCheck check = new DlrStorageReadinessCheck();
        check.storage = storage;

        HealthCheckResponse response = check.call();
        Map<String, Object> data = response.getData().orElseThrow();

        assertThat(response.getStatus()).isEqualTo(HealthCheckResponse.Status.UP);
        assertThat(data).containsEntry("backend", "mvstore").containsEntry("mode", "memory");
    }

    @Test
    void reportsPostgresqlSchemaAsReady() throws SQLException {
        ConfiguredDlrStorage storage = mock(ConfiguredDlrStorage.class);
        when(storage.backend()).thenReturn("postgresql");
        DlrStorageReadinessCheck check = new DlrStorageReadinessCheck();
        check.storage = storage;

        HealthCheckResponse response = check.call();
        Map<String, Object> data = response.getData().orElseThrow();

        assertThat(response.getStatus()).isEqualTo(HealthCheckResponse.Status.UP);
        assertThat(data).containsOnlyKeys("backend");
    }

    @Test
    void reportsSanitizedPostgresqlFailure() throws SQLException {
        ConfiguredDlrStorage storage = mock(ConfiguredDlrStorage.class);
        when(storage.backend()).thenReturn("postgresql");
        doThrow(new SQLException("jdbc:postgresql://secret-host/database"))
                .when(storage).verifyPostgresqlSchema();
        DlrStorageReadinessCheck check = new DlrStorageReadinessCheck();
        check.storage = storage;

        HealthCheckResponse response = check.call();
        Map<String, Object> data = response.getData().orElseThrow();

        assertThat(response.getStatus()).isEqualTo(HealthCheckResponse.Status.DOWN);
        assertThat(data)
                .containsEntry("backend", "postgresql")
                .containsEntry("reason", "unavailable");
        assertThat(data.toString()).doesNotContain("secret-host");
    }
}
