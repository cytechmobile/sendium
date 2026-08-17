package gr.cytech.sendium.core.worker;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.Map;

public class PostgresqlDlrQuarkusTestResource implements QuarkusTestResourceLifecycleManager {
    private PostgreSQLContainer postgresql;

    @Override
    public Map<String, String> start() {
        if (!Boolean.getBoolean("sendium.postgresql.tests")) {
            return Map.of();
        }

        postgresql = new PostgreSQLContainer("postgres:17-alpine")
                .withDatabaseName("sendium")
                .withUsername("sendium")
                .withPassword("sendium-test");
        postgresql.start();
        return Map.of(
                "sendium.dlr.storage", "postgresql",
                "quarkus.datasource.dlr.active", "true",
                "quarkus.flyway.dlr.active", "true",
                "quarkus.flyway.dlr.migrate-at-start", "true",
                "quarkus.datasource.dlr.jdbc.url", postgresql.getJdbcUrl(),
                "quarkus.datasource.dlr.username", postgresql.getUsername(),
                "quarkus.datasource.dlr.password", postgresql.getPassword());
    }

    @Override
    public void stop() {
        if (postgresql != null) {
            postgresql.stop();
        }
    }
}
