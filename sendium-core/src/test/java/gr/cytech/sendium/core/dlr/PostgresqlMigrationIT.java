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
    private static final UUID INVALID_STATUS_GATEWAY_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID CORRELATION_GATEWAY_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID COMPLETE_GATEWAY_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000003");
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
    void migrationCreatesExpectedTablesAndIndexes() throws SQLException {
        assertThat(initialMigration.success).isTrue();
        assertThat(initialMigration.migrationsExecuted).isOne();

        try (Connection connection = connection()) {
            assertThat(loadNames(connection,
                    "SELECT table_name FROM information_schema.tables WHERE table_schema = 'sendium_dlr'"))
                    .containsExactlyInAnyOrder("tracked_message", "provider_correlation", "unpushed_dlr");
            assertThat(loadNames(connection,
                    "SELECT indexname FROM pg_indexes WHERE schemaname = 'sendium_dlr'"))
                    .contains("tracked_message_created_at_idx",
                            "tracked_message_provider_message_id_idx",
                            "provider_correlation_created_at_idx",
                            "provider_correlation_gateway_message_idx",
                            "unpushed_dlr_system_created_at_idx",
                            "unpushed_dlr_created_at_idx");
            assertThat(loadColumnType(connection, "tracked_message", "gateway_message_id"))
                    .isEqualTo("uuid");
            assertThat(loadColumnType(connection, "provider_correlation", "gateway_message_id"))
                    .isEqualTo("uuid");
            assertThat(loadColumnType(connection, "provider_correlation", "provider_name"))
                    .isEqualTo("text");
        }
    }

    @Test
    void migrationIsIdempotent() {
        MigrateResult repeatedMigration = flyway.migrate();

        assertThat(repeatedMigration.success).isTrue();
        assertThat(repeatedMigration.migrationsExecuted).isZero();
    }

    @Test
    void trackedMessageRejectsUnknownStatus() throws SQLException {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO sendium_dlr.tracked_message
                         (gateway_message_id, account_id, system_id, status)
                     VALUES (?, 'account', 'system', 'UNKNOWN')
                     """)) {
            statement.setObject(1, INVALID_STATUS_GATEWAY_ID);
            assertThatThrownBy(statement::executeUpdate).isInstanceOf(SQLException.class);
        }
    }

    @Test
    void providerCorrelationFieldsRejectBlankValues() throws SQLException {
        try (Connection connection = connection()) {
            for (String blank : List.of("", "   ", "\t\n")) {
                assertThatThrownBy(() -> insertTrackedMessageWithProvider(
                        connection, UUID.randomUUID(), blank, "provider-message"))
                        .isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> insertTrackedMessageWithProvider(
                        connection, UUID.randomUUID(), "provider", blank))
                        .isInstanceOf(SQLException.class);
            }

            UUID gatewayMessageId = UUID.randomUUID();
            insertTrackedMessage(connection, gatewayMessageId);
            assertThatThrownBy(() -> insertCorrelation(connection, "   ", "provider-message", gatewayMessageId))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> insertCorrelation(connection, "provider", "\t\n", gatewayMessageId))
                    .isInstanceOf(SQLException.class);
        }
    }

    @Test
    void correlationIsDeletedWithTrackedMessage() throws SQLException {
        try (Connection connection = connection()) {
            insertTrackedMessage(connection, CORRELATION_GATEWAY_ID);
            insertCorrelation(connection, "provider-1", "provider-message-1", CORRELATION_GATEWAY_ID);
            insertCorrelation(connection, "provider-1", "provider-message-2", CORRELATION_GATEWAY_ID);

            try (PreparedStatement statement = connection.prepareStatement("""
                    DELETE FROM sendium_dlr.tracked_message WHERE gateway_message_id = ?
                    """)) {
                statement.setObject(1, CORRELATION_GATEWAY_ID);
                statement.executeUpdate();
            }

            assertThat(countCorrelations(connection, CORRELATION_GATEWAY_ID)).isZero();
        }
    }

    @Test
    void unpushedDlrRequiresNonBlankSystemId() throws SQLException {
        try (Connection connection = connection()) {
            assertThatThrownBy(() -> insertMinimalUnpushedDlr(connection, "empty-system", ""))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> insertMinimalUnpushedDlr(connection, "whitespace-system", "   "))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> insertMinimalUnpushedDlr(connection, "control-whitespace-system", "\t\n"))
                    .isInstanceOf(SQLException.class);
        }
    }

    @Test
    void typedColumnsStoreCurrentDlrState() throws SQLException {
        try (Connection connection = connection()) {
            insertCompleteTrackedMessage(connection);
            insertCompleteUnpushedDlr(connection);

            try (PreparedStatement statement = connection.prepareStatement("""
                         SELECT gateway_message_id, provider_message_id, reassembled_parts, created_at, updated_at
                         FROM sendium_dlr.tracked_message
                         WHERE gateway_message_id = ?
                         """)) {
                statement.setObject(1, COMPLETE_GATEWAY_ID);
                try (ResultSet resultSet = statement.executeQuery()) {
                    assertThat(resultSet.next()).isTrue();
                    assertThat(resultSet.getObject("gateway_message_id", UUID.class))
                            .isEqualTo(COMPLETE_GATEWAY_ID);
                    assertThat(resultSet.getString("provider_message_id")).isNull();
                    assertThat((String[]) resultSet.getArray("reassembled_parts").getArray())
                            .containsExactly("part-1", "part-2");
                    assertThat(resultSet.getObject("created_at")).isNotNull();
                    assertThat(resultSet.getObject("updated_at")).isNotNull();
                }
            }

            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery("""
                         SELECT account_id, message_id, dlr_state, error_code, acked, priority, reassembled_parts
                         FROM sendium_dlr.unpushed_dlr
                         WHERE dlr_key = 'dlr-complete'
                         """)) {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getString("account_id")).isEqualTo("account");
                assertThat(resultSet.getInt("message_id")).isEqualTo(123);
                assertThat(resultSet.getInt("dlr_state")).isEqualTo(1);
                assertThat(resultSet.getString("error_code")).isEqualTo("0");
                assertThat(resultSet.getBoolean("acked")).isTrue();
                assertThat(resultSet.getInt("priority")).isEqualTo(2);
                assertThat((String[]) resultSet.getArray("reassembled_parts").getArray())
                        .containsExactly("part-1", "part-2");
            }
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

    private static void insertTrackedMessage(Connection connection, UUID gatewayMessageId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO sendium_dlr.tracked_message
                    (gateway_message_id, account_id, system_id, status)
                VALUES (?, 'account', 'system', 'ACCEPTED')
                """)) {
            statement.setObject(1, gatewayMessageId);
            statement.executeUpdate();
        }
    }

    private static void insertTrackedMessageWithProvider(Connection connection, UUID gatewayMessageId,
                                                          String providerName,
                                                          String providerMessageId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO sendium_dlr.tracked_message
                    (gateway_message_id, provider_name, provider_message_id, status)
                VALUES (?, ?, ?, 'ACCEPTED')
                """)) {
            statement.setObject(1, gatewayMessageId);
            statement.setString(2, providerName);
            statement.setString(3, providerMessageId);
            statement.executeUpdate();
        }
    }

    private static void insertCorrelation(Connection connection, String providerName, String providerMessageId,
                                           UUID gatewayMessageId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO sendium_dlr.provider_correlation
                    (provider_name, provider_message_id, gateway_message_id)
                VALUES (?, ?, ?)
                """)) {
            statement.setString(1, providerName);
            statement.setString(2, providerMessageId);
            statement.setObject(3, gatewayMessageId);
            statement.executeUpdate();
        }
    }

    private static void insertMinimalUnpushedDlr(Connection connection, String key,
                                                 String systemId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO sendium_dlr.unpushed_dlr
                    (dlr_key, system_id, message_id, dlr_state, acked, priority)
                VALUES (?, ?, 1, 1, FALSE, 0)
                """)) {
            statement.setString(1, key);
            statement.setString(2, systemId);
            statement.executeUpdate();
        }
    }

    private static void insertCompleteTrackedMessage(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO sendium_dlr.tracked_message
                    (gateway_message_id, account_id, system_id, source_address, destination_address,
                     forward_dlr_url, reassembled_parts, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setObject(1, COMPLETE_GATEWAY_ID);
            statement.setString(2, "account");
            statement.setString(3, "system");
            statement.setString(4, "source");
            statement.setString(5, "destination");
            statement.setString(6, "https://example.test/dlr");
            statement.setArray(7, connection.createArrayOf("text", new String[]{"part-1", "part-2"}));
            statement.setString(8, "ACCEPTED");
            statement.executeUpdate();
        }
    }

    private static void insertCompleteUnpushedDlr(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO sendium_dlr.unpushed_dlr
                    (dlr_key, system_id, account_id, source_address, destination_address, serial,
                     message_id, dlr_state, error_code, acked, priority, reassembled_parts)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, "dlr-complete");
            statement.setString(2, "system");
            statement.setString(3, "account");
            statement.setString(4, "source");
            statement.setString(5, "destination");
            statement.setString(6, "serial");
            statement.setInt(7, 123);
            statement.setInt(8, 1);
            statement.setString(9, "0");
            statement.setBoolean(10, true);
            statement.setInt(11, 2);
            statement.setArray(12, connection.createArrayOf("text", new String[]{"part-1", "part-2"}));
            statement.executeUpdate();
        }
    }

    private static int countCorrelations(Connection connection, UUID gatewayMessageId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(*)
                FROM sendium_dlr.provider_correlation
                WHERE gateway_message_id = ?
                """)) {
            statement.setObject(1, gatewayMessageId);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1);
            }
        }
    }

    private static String loadColumnType(Connection connection, String tableName,
                                         String columnName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT data_type
                FROM information_schema.columns
                WHERE table_schema = 'sendium_dlr'
                    AND table_name = ?
                    AND column_name = ?
                """)) {
            statement.setString(1, tableName);
            statement.setString(2, columnName);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                return resultSet.getString(1);
            }
        }
    }
}
