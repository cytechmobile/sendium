package gr.cytech.sendium.app.storage;

import gr.cytech.sendium.core.outbound.OutboundCoordinator;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MessageStorageReadinessTest {
    @Test
    void storageHealthIsDownForUnsafeRecoveryShutdownOrProbeFailure() {
        var health = new MessageStorageReadiness();
        health.coordinator = mock(OutboundCoordinator.class);
        when(health.coordinator.isReady()).thenReturn(true, false).thenThrow(new IllegalStateException("private details"));
        var ready = health.call();
        assertThat(ready.getName()).isEqualTo("sendium-sms-storage");
        assertThat(ready.getStatus()).isEqualTo(HealthCheckResponse.Status.UP);
        assertThat(health.call().getStatus()).isEqualTo(HealthCheckResponse.Status.DOWN);
        var failed = health.call();
        assertThat(failed.getStatus()).isEqualTo(HealthCheckResponse.Status.DOWN);
        assertThat(failed.getData()).isEmpty();
    }
}
