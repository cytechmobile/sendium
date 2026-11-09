package gr.cytech.sendium.app.storage;

import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.outbound.OutboundCoordinator;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

/** Constant-size status probes only: health requests never enumerate accepted sources. */
@ApplicationScoped
@Readiness
public class MessageStorageReadiness implements HealthCheck {
    @Inject OutboundCoordinator<StandardMessage> coordinator;

    @Override
    public HealthCheckResponse call() {
        boolean ready;
        try {
            ready = coordinator.isReady();
        } catch (RuntimeException failure) {
            ready = false;
        }
        return HealthCheckResponse.named("sendium-sms-storage").status(ready).build();
    }
}
