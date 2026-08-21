package gr.cytech.sendium.core.dlr;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PostgresqlMigrationIT {
    private static final String MIGRATION_LOCATION = "classpath:db/sendium-dlr/postgresql";
    private static final PostgreSQLContainer POSTGRESQL = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("sendium")
            .withUsername("sendium")
            .withPassword("sendium-test");

    private static Flyway flyway;
    private static MigrateResult initialMigration;

    @BeforeAll
    static void migrateSchema() {
        POSTGRESQL.start();
        flyway = Flyway.configure()
                .dataSource(POSTGRESQL.getJdbcUrl(), POSTGRESQL.getUsername(), POSTGRESQL.getPassword())
                .locations(MIGRATION_LOCATION)
                .load();
        initialMigration = flyway.migrate();
    }

    @AfterAll
    static void stopPostgresql() {
        POSTGRESQL.stop();
    }

    @Test
    void migrationCreatesDlrMessageSchemaAndPartialIndexes() throws SQLException {
        assertThat(initialMigration.success).isTrue();
        assertThat(initialMigration.migrationsExecuted).isOne();

        try (Connection connection = connection()) {
            assertThat(loadNames(connection,
                    "SELECT table_name FROM information_schema.tables WHERE table_schema = 'sendium_dlr'"))
                    .containsExactlyInAnyOrder("dlr_message", "provider_correlation");
            assertThat(loadNames(connection,
                    "SELECT indexname FROM pg_indexes WHERE schemaname = 'sendium_dlr'"))
                    .contains("dlr_message_created_at_idx",
                            "dlr_message_provider_message_id_idx",
                            "dlr_message_http_due_idx",
                            "dlr_message_smpp_replay_idx",
                            "provider_correlation_created_at_idx",
                            "provider_correlation_gateway_message_idx");
            assertThat(loadIndexDefinition(connection, "dlr_message_http_due_idx"))
                    .contains("next_attempt_at")
                    .contains("delivery_channel = 'HTTP'")
                    .contains("delivery_status = 'PENDING'");
            assertThat(loadIndexDefinition(connection, "dlr_message_smpp_replay_idx"))
                    .contains("system_id", "resolved_at")
                    .contains("delivery_channel = 'SMPP'")
                    .contains("delivery_status = 'PENDING'");
            assertThat(loadColumnType(connection, "dlr_message", "gateway_message_id")).isEqualTo("uuid");
            assertThat(loadColumnNames(connection, "dlr_message"))
                    .contains("dlr_state", "error_code", "delivery_channel", "delivery_status",
                            "delivery_attempt_count", "last_attempt_at", "next_attempt_at",
                            "last_delivery_result", "resolved_at")
                    .doesNotContain("generation_id");
        }
    }

    @Test
    void migrationIsIdempotent() {
        MigrateResult repeatedMigration = flyway.migrate();

        assertThat(repeatedMigration.success).isTrue();
        assertThat(repeatedMigration.migrationsExecuted).isZero();
    }

    @Test
    void schemaRejectsInvalidProviderAndDeliveryStates() throws SQLException {
        try (Connection connection = connection()) {
            assertInvalidMessage(connection, "UNKNOWN", "NONE", "WAITING_PROVIDER", 0, null, null);
            assertInvalidMessage(connection, "ACCEPTED", "MAIL", "WAITING_PROVIDER", 0, null, null);
            assertInvalidMessage(connection, "ACCEPTED", "NONE", "DONE", 0, null, null);
            assertInvalidMessage(connection, "ACCEPTED", "NONE", "WAITING_PROVIDER", -1, null, null);
        }
    }

    @Test
    void schemaRequiresValidChannelTargets() throws SQLException {
        try (Connection connection = connection()) {
            for (String blank : List.of("", "   ", "\t\n")) {
                assertInvalidMessage(connection, "ACCEPTED", "HTTP", "WAITING_PROVIDER", 0, "system", blank);
                assertInvalidMessage(connection, "ACCEPTED", "SMPP", "WAITING_PROVIDER", 0, blank,
                        "https://example.test/dlr");
            }
            assertInvalidMessage(connection, "ACCEPTED", "HTTP", "WAITING_PROVIDER", 0, "system", null);
            assertInvalidMessage(connection, "ACCEPTED", "SMPP", "WAITING_PROVIDER", 0, null,
                    "https://example.test/dlr");
        }
    }

    @Test
    void providerCorrelationReferencesDlrMessageAndCascades() throws SQLException {
        UUID gatewayId = UUID.randomUUID();
        try (Connection connection = connection()) {
            insertMessage(connection, gatewayId);
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO sendium_dlr.provider_correlation
                        (provider_name, provider_message_id, gateway_message_id)
                    VALUES ('provider', 'message', ?)
                    """)) {
                statement.setObject(1, gatewayId);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM sendium_dlr.dlr_message WHERE gateway_message_id = ?")) {
                statement.setObject(1, gatewayId);
                statement.executeUpdate();
            }
            assertThat(loadCount(connection, "sendium_dlr.provider_correlation")).isZero();
        }
    }

    @Test
    void defaultsWaitingProviderWithNoAttempts() throws SQLException {
        UUID gatewayId = UUID.randomUUID();
        try (Connection connection = connection()) {
            insertMessage(connection, gatewayId);
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT delivery_channel, delivery_status, delivery_attempt_count
                    FROM sendium_dlr.dlr_message WHERE gateway_message_id = ?
                    """)) {
                statement.setObject(1, gatewayId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    assertThat(resultSet.next()).isTrue();
                    assertThat(resultSet.getString("delivery_channel")).isEqualTo("NONE");
                    assertThat(resultSet.getString("delivery_status")).isEqualTo("WAITING_PROVIDER");
                    assertThat(resultSet.getInt("delivery_attempt_count")).isZero();
                }
            }
        }
    }

    private static void assertInvalidMessage(Connection connection, String providerStatus, String channel,
                                             String deliveryStatus, int attempts, String systemId,
                                             String callbackUrl) {
        assertThatThrownBy(() -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO sendium_dlr.dlr_message
                        (gateway_message_id, provider_status, delivery_channel, delivery_status,
                         delivery_attempt_count, system_id, forward_dlr_url)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """)) {
                statement.setObject(1, UUID.randomUUID());
                statement.setString(2, providerStatus);
                statement.setString(3, channel);
                statement.setString(4, deliveryStatus);
                statement.setInt(5, attempts);
                statement.setString(6, systemId);
                statement.setString(7, callbackUrl);
                statement.executeUpdate();
            }
        }).isInstanceOf(SQLException.class);
    }

    private static void insertMessage(Connection connection, UUID gatewayId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO sendium_dlr.dlr_message (gateway_message_id, provider_status)
                VALUES (?, 'ACCEPTED')
                """)) {
            statement.setObject(1, gatewayId);
            statement.executeUpdate();
        }
    }

    private static Connection connection() throws SQLException {
        return POSTGRESQL.createConnection("");
    }

    private static Set<String> loadNames(Connection connection, String sql) throws SQLException {
        Set<String> names = new HashSet<>();
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            while (resultSet.next()) {
                names.add(resultSet.getString(1));
            }
        }
        return names;
    }

    private static Set<String> loadColumnNames(Connection connection, String tableName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = 'sendium_dlr' AND table_name = ?
                """)) {
            statement.setString(1, tableName);
            Set<String> names = new HashSet<>();
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    names.add(resultSet.getString(1));
                }
            }
            return names;
        }
    }

    private static String loadColumnType(Connection connection, String tableName,
                                         String columnName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT data_type FROM information_schema.columns
                WHERE table_schema = 'sendium_dlr' AND table_name = ? AND column_name = ?
                """)) {
            statement.setString(1, tableName);
            statement.setString(2, columnName);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                return resultSet.getString(1);
            }
        }
    }

    private static String loadIndexDefinition(Connection connection, String indexName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT indexdef FROM pg_indexes WHERE schemaname = 'sendium_dlr' AND indexname = ?
                """)) {
            statement.setString(1, indexName);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                return resultSet.getString(1);
            }
        }
    }

    private static int loadCount(Connection connection, String table) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }
}
