package gr.cytech.sendium.core.worker;

import gr.cytech.sendium.util.MessageTrace;
import io.quarkus.arc.properties.IfBuildProperty;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.eclipse.microprofile.health.Readiness;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Readiness
@ApplicationScoped
@IfBuildProperty(name = "sendium.dlr.persistence.enabled", stringValue = "true", enableIfMissing = false)
public class DlrStorageReadinessCheck implements HealthCheck {
    private static final Logger logger = LoggerFactory.getLogger(DlrStorageReadinessCheck.class);
    private static final String CHECK_NAME = "sendium-dlr-storage";

    @Inject
    ManagedDlrStorage storage;

    @Override
    public HealthCheckResponse call() {
        HealthCheckResponseBuilder response = HealthCheckResponse.named(CHECK_NAME);
        String backend = "unknown";
        try {
            backend = storage.backend();
            response.withData("backend", backend);
            storage.verifyAvailable();
            return response.up().build();
        } catch (RuntimeException e) {
            // An unchecked escape would otherwise reach SmallRye, which replaces the whole payload with the raw
            // exception message and drops both the check name and the sanitized reason. Keep details in the log only.
            logger.warn("dlrStorageReadiness|error|backend:{}|reason:unavailable", MessageTrace.value(backend), e);
            return down(response);
        }
    }

    private HealthCheckResponse down(HealthCheckResponseBuilder response) {
        return response.down()
                .withData("reason", "unavailable")
                .build();
    }
}
