package gr.cytech.sendium.core.worker;

import org.eclipse.microprofile.health.HealthCheckResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.SQLException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DlrStorageReadinessCheckTest {
    @ParameterizedTest
    @ValueSource(strings = {"full", "recovery", "disabled"})
    void nonDurableModeReadinessReflectsAvailability(String scenario) {
        var storage = DisabledDlrProcessingTest.selected(scenario.equals("disabled") ? "disabled" : "memory");
        try {
            if (!scenario.equals("disabled")) {
                var owner = (MemoryDlrReservations) DisabledDlrProcessingTest.policy(storage);
                if (scenario.equals("full")) {
                    for (int i = 0; i < owner.maxCapacity(); i++) {
                        MemoryDlrTestSupport.reserve(owner);
                    }
                } else {
                    assertThat(owner.beginRecovery((long) owner.maxCapacity() + 1)).isFalse();
                    assertThat(owner.usedCapacity()).isZero();
                }
            }
            var check = new DlrStorageReadinessCheck();
            check.storage = storage;
            assertThat(check.call().getStatus()).isEqualTo(HealthCheckResponse.Status.UP);
            assertThat(check.call().getData().orElseThrow()).containsEntry("backend",
                    scenario.equals("disabled") ? "disabled" : "memory");
            storage.close();
            assertThat(check.call().getStatus()).isEqualTo(HealthCheckResponse.Status.DOWN);
        } finally {
            storage.close();
        }
    }

    @Test
    void reportsPostgresqlSchemaAsReady() throws SQLException {
        ManagedDlrStorage storage = mock(ManagedDlrStorage.class);
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
        ManagedDlrStorage storage = mock(ManagedDlrStorage.class);
        when(storage.backend()).thenReturn("postgresql");
        doThrow(new DlrStorageException("dlrStorageAvailability|error|backend:postgresql|reason:schemaProbeFailed",
                new SQLException("jdbc:postgresql://secret-host/database")))
                .when(storage).verifyAvailable();
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
