package gr.cytech.sendium.core.worker;

import io.agroal.api.AgroalDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.agroal.DataSource;
import io.quarkus.arc.InjectableInstance;
import io.quarkus.flyway.FlywayDataSource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@QuarkusTestResource(value = PostgresqlDlrQuarkusTestResource.class, restrictToAnnotatedClass = true)
@EnabledIfSystemProperty(named = "sendium.postgresql.tests", matches = "true")
class PostgresqlDlrRuntimeTest {
    @Inject
    DlrStorage storage;

    @Inject
    ConfiguredDlrStorage configuredStorage;

    @Inject
    @DataSource("dlr")
    InjectableInstance<AgroalDataSource> dataSource;

    @Inject
    @FlywayDataSource("dlr")
    InjectableInstance<Flyway> flyway;

    @Inject
    MeterRegistry meterRegistry;

    @Test
    void wiresPoolMigrationStorageHealthAndMetrics() throws SQLException {
        assertThat(storage).isSameAs(configuredStorage);
        assertThat(configuredStorage.backend()).isEqualTo("postgresql");
        assertThat(dataSource.getHandle().getBean().isActive()).isTrue();
        assertThat(flyway.getHandle().getBean().isActive()).isTrue();
        assertThat(flyway.get().info().current().getVersion().getVersion()).isEqualTo("1");
        assertThat(flywayHistoryCount()).isOne();

        MessageState state = new MessageState(UUID.randomUUID().toString(), "account", "system",
                "source", "destination", null);
        storage.saveInitialState(state);
        assertThat(storage.getState(state.getGatewayMsgId())).isPresent();

        given()
                .when().get("/q/health/ready")
                .then()
                .statusCode(200)
                .body("status", equalTo("UP"))
                .body("checks.find { it.name == 'sendium-dlr-storage' }.data.backend",
                        equalTo("postgresql"));

        assertThat(meterRegistry.find("sendium.dlr.storage.selected")
                .tag("backend", "postgresql").gauge().value()).isEqualTo(1.0);
        assertThat(meterRegistry.find("sendium.dlr.storage.operation")
                .tags("backend", "postgresql", "operation", "save_initial", "outcome", "success")
                .timer().count()).isOne();
        assertThat(meterRegistry.getMeters())
                .extracting(meter -> meter.getId().getName())
                .anyMatch(name -> name.startsWith("agroal"));
    }

    private int flywayHistoryCount() throws SQLException {
        try (Connection connection = dataSource.get().getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     SELECT COUNT(*)
                     FROM sendium_dlr.flyway_schema_history
                     WHERE success AND version = '1'
                     """)) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }
}
