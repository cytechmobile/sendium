package gr.cytech.sendium.core.worker;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.eclipse.microprofile.health.Readiness;

import java.sql.SQLException;

@Readiness
@ApplicationScoped
public class DlrStorageReadinessCheck implements HealthCheck {
    private static final String CHECK_NAME = "sendium-dlr-storage";

    @Inject
    ConfiguredDlrStorage storage;

    @Override
    public HealthCheckResponse call() {
        HealthCheckResponseBuilder response = HealthCheckResponse.named(CHECK_NAME)
                .withData("backend", storage.backend());
        if (!"postgresql".equals(storage.backend())) {
            return response.up()
                    .withData("mode", storage.mode())
                    .build();
        }

        try {
            storage.verifyPostgresqlSchema();
            return response.up().build();
        } catch (SQLException e) {
            return response.down()
                    .withData("reason", "unavailable")
                    .build();
        }
    }
}
