package gr.cytech.sendium.core.worker;

import io.quarkus.arc.properties.IfBuildProperty;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.eclipse.microprofile.health.Readiness;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;

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
        try {
            response.withData("backend", storage.backend());
            storage.verifyPostgresqlSchema();
            return response.up().build();
        } catch (SQLException e) {
            // The probe result stays sanitized; the cause is only logged so an outage remains diagnosable.
            logger.warn("DLR storage readiness probe failed: sqlState={} errorCode={} reason={}",
                    e.getSQLState(), e.getErrorCode(), e.getMessage(), e);
            return down(response);
        } catch (RuntimeException e) {
            // An unchecked escape would otherwise reach SmallRye, which replaces the whole payload with the raw
            // exception message and drops both the check name and the sanitized reason.
            logger.warn("DLR storage readiness probe failed unexpectedly", e);
            return down(response);
        }
    }

    private HealthCheckResponse down(HealthCheckResponseBuilder response) {
        return response.down()
                .withData("reason", "unavailable")
                .build();
    }
}
