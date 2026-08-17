package gr.cytech.sendium.core.worker;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIfSystemProperty(named = "sendium.postgresql.tests", matches = "true")
class PostgresqlMessageStateStorageTest {
    private static final String MIGRATION_LOCATION = "classpath:db/sendium-dlr/postgresql";
    private static final PostgreSQLContainer POSTGRESQL = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("sendium")
            .withUsername("sendium")
            .withPassword("sendium-test");

    private static DataSource dataSource;

    private PostgresqlMessageStateStorage storage;

    @BeforeAll
    static void startPostgresql() {
        POSTGRESQL.start();
        Flyway.configure()
                .dataSource(POSTGRESQL.getJdbcUrl(), POSTGRESQL.getUsername(), POSTGRESQL.getPassword())
                .locations(MIGRATION_LOCATION)
                .load()
                .migrate();

        PGSimpleDataSource postgresDataSource = new PGSimpleDataSource();
        postgresDataSource.setUrl(POSTGRESQL.getJdbcUrl());
        postgresDataSource.setUser(POSTGRESQL.getUsername());
        postgresDataSource.setPassword(POSTGRESQL.getPassword());
        dataSource = postgresDataSource;
    }

    @AfterAll
    static void stopPostgresql() {
        POSTGRESQL.stop();
    }

    @BeforeEach
    void resetStorage() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("TRUNCATE sendium_dlr.tracked_message CASCADE");
        }
        storage = new PostgresqlMessageStateStorage(dataSource);
    }

    @Test
    void saveInitialStateRoundTripsAllFields() {
        MessageState state = newState();
        state.setOperatorMsgId("operator-initial");
        state.setReassembledParts(List.of("part-1", "part-2"));

        storage.saveInitialState(state);

        assertThat(storage.getState(state.getGatewayMsgId()))
                .get()
                .usingRecursiveComparison()
                .isEqualTo(state);
        assertThat(storage.resolveAndRemoveDlr("operator-initial", MessageState.MessageStatus.DELIVERED))
                .isPresent();
    }

    @Test
    void saveInitialStateOverwritesExistingState() throws SQLException {
        MessageState initial = newState();
        storage.saveInitialState(initial);
        storage.linkOperatorId(initial.getGatewayMsgId(), "operator-old");

        MessageState replacement = new MessageState(initial.getGatewayMsgId(), "replacement-account",
                "replacement-system", "replacement-source", "replacement-destination", null);
        replacement.setStatus(MessageState.MessageStatus.FAILED);
        storage.saveInitialState(replacement);

        assertThat(storage.getState(initial.getGatewayMsgId()))
                .get()
                .usingRecursiveComparison()
                .isEqualTo(replacement);
        assertThat(countCorrelations(initial.getGatewayMsgId())).isZero();
        assertThat(storage.resolveAndRemoveDlr("operator-old", MessageState.MessageStatus.DELIVERED))
                .isEmpty();
    }

    @Test
    void saveInitialStateRollsBackOnCorrelationOwnedByAnotherMessage() throws SQLException {
        MessageState owner = newState();
        storage.saveInitialState(owner);
        storage.linkOperatorId(owner.getGatewayMsgId(), "shared-operator");

        MessageState target = newState();
        storage.saveInitialState(target);
        storage.linkOperatorId(target.getGatewayMsgId(), "target-operator");
        MessageState targetBeforeReplacement = storage.getState(target.getGatewayMsgId()).orElseThrow();

        MessageState replacement = new MessageState(target.getGatewayMsgId(), "replacement-account",
                "replacement-system", "replacement-source", "replacement-destination", null);
        replacement.setOperatorMsgId("shared-operator");
        assertThatThrownBy(() -> storage.saveInitialState(replacement))
                .isInstanceOf(DlrStorageException.class);

        assertThat(storage.getState(target.getGatewayMsgId()))
                .get()
                .usingRecursiveComparison()
                .isEqualTo(targetBeforeReplacement);
        assertThat(storage.getState(owner.getGatewayMsgId()).orElseThrow().getOperatorMsgId())
                .isEqualTo("shared-operator");
        assertThat(countCorrelations(target.getGatewayMsgId())).isOne();
        assertThat(countCorrelations(owner.getGatewayMsgId())).isOne();

        MessageState newConflict = newState();
        newConflict.setOperatorMsgId("shared-operator");
        assertThatThrownBy(() -> storage.saveInitialState(newConflict))
                .isInstanceOf(DlrStorageException.class);
        assertThat(storage.getState(newConflict.getGatewayMsgId())).isEmpty();
    }

    @Test
    void linkOperatorIdUpdatesStateAndKeepsMultipleCorrelations() throws SQLException {
        MessageState state = newState();
        storage.saveInitialState(state);

        storage.linkOperatorId(state.getGatewayMsgId(), "operator-1");
        storage.linkOperatorId(state.getGatewayMsgId(), "operator-2");

        MessageState linked = storage.getState(state.getGatewayMsgId()).orElseThrow();
        assertThat(linked.getStatus()).isEqualTo(MessageState.MessageStatus.SENT);
        assertThat(linked.getOperatorMsgId()).isEqualTo("operator-2");
        assertThat(countCorrelations(state.getGatewayMsgId())).isEqualTo(2);
    }

    @Test
    void linkOperatorIdRollsBackStateWhenCorrelationInsertFails() {
        MessageState state = newState();
        storage.saveInitialState(state);

        assertThatThrownBy(() -> storage.linkOperatorId(state.getGatewayMsgId(), null))
                .isInstanceOf(DlrStorageException.class);

        MessageState unchanged = storage.getState(state.getGatewayMsgId()).orElseThrow();
        assertThat(unchanged.getStatus()).isEqualTo(MessageState.MessageStatus.ACCEPTED);
        assertThat(unchanged.getOperatorMsgId()).isNull();
    }

    @Test
    void linkOperatorIdRejectsCorrelationOwnedByAnotherMessage() throws SQLException {
        MessageState first = newState();
        MessageState second = newState();
        storage.saveInitialState(first);
        storage.saveInitialState(second);
        storage.linkOperatorId(first.getGatewayMsgId(), "shared-operator");

        assertThatThrownBy(() -> storage.linkOperatorId(second.getGatewayMsgId(), "shared-operator"))
                .isInstanceOf(DlrStorageException.class);

        assertThat(storage.getState(first.getGatewayMsgId()).orElseThrow().getOperatorMsgId())
                .isEqualTo("shared-operator");
        MessageState unchanged = storage.getState(second.getGatewayMsgId()).orElseThrow();
        assertThat(unchanged.getStatus()).isEqualTo(MessageState.MessageStatus.ACCEPTED);
        assertThat(unchanged.getOperatorMsgId()).isNull();
        assertThat(countCorrelations(first.getGatewayMsgId())).isOne();
        assertThat(countCorrelations(second.getGatewayMsgId())).isZero();
    }

    @Test
    void linkOperatorIdFailsWhenGatewayStateDoesNotAppear() {
        PostgresqlMessageStateStorage noRetryStorage =
                new PostgresqlMessageStateStorage(dataSource, 1, 0);

        assertThatThrownBy(() -> noRetryStorage.linkOperatorId(UUID.randomUUID().toString(), "operator"))
                .isInstanceOf(DlrStorageException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void resolveAndRemoveDlrReturnsUpdatedStateAndDeletesAllCorrelations() throws SQLException {
        MessageState state = newState();
        storage.saveInitialState(state);
        storage.linkOperatorId(state.getGatewayMsgId(), "operator-1");
        storage.linkOperatorId(state.getGatewayMsgId(), "operator-2");
        long beforeResolve = System.currentTimeMillis();

        Optional<MessageState> resolved = storage.resolveAndRemoveDlr(
                "operator-1", MessageState.MessageStatus.DELIVERED);

        assertThat(resolved).isPresent();
        assertThat(resolved.orElseThrow().getStatus()).isEqualTo(MessageState.MessageStatus.DELIVERED);
        assertThat(resolved.orElseThrow().getOperatorMsgId()).isEqualTo("operator-1");
        assertThat(resolved.orElseThrow().getTimestamp()).isGreaterThanOrEqualTo(beforeResolve);
        assertThat(storage.getState(state.getGatewayMsgId())).isEmpty();
        assertThat(countCorrelations(state.getGatewayMsgId())).isZero();
    }

    @Test
    void concurrentResolveAcrossCorrelationsReturnsStateOnlyOnce() throws Exception {
        MessageState state = newState();
        storage.saveInitialState(state);
        storage.linkOperatorId(state.getGatewayMsgId(), "operator-1");
        storage.linkOperatorId(state.getGatewayMsgId(), "operator-2");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<Optional<MessageState>> first = executor.submit(
                    () -> resolveWhenReleased("operator-1", ready, start));
            Future<Optional<MessageState>> second = executor.submit(
                    () -> resolveWhenReleased("operator-2", ready, start));
            ready.await();
            start.countDown();

            assertThat(List.of(first.get(), second.get()).stream().filter(Optional::isPresent).count())
                    .isOne();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void markAsFailedUpdatesExistingState() {
        MessageState state = newState();
        storage.saveInitialState(state);

        boolean updated = storage.markAsFailed(state.getGatewayMsgId());

        assertThat(updated).isTrue();
        assertThat(storage.getState(state.getGatewayMsgId()).orElseThrow().getStatus())
                .isEqualTo(MessageState.MessageStatus.FAILED);
        assertThat(storage.markAsFailed(UUID.randomUUID().toString())).isFalse();
    }

    @Test
    void expiryRemovesOldCorrelationsAndMessages() throws SQLException {
        MessageState correlationState = newState();
        storage.saveInitialState(correlationState);
        storage.linkOperatorId(correlationState.getGatewayMsgId(), "old-correlation");
        ageCorrelation("old-correlation");

        PostgresqlMessageStateStorage correlationCleanup = new PostgresqlMessageStateStorage(dataSource);
        assertThat(correlationCleanup.getState(correlationState.getGatewayMsgId())).isPresent();
        assertThat(countCorrelations(correlationState.getGatewayMsgId())).isZero();

        MessageState oldMessage = newState();
        storage.saveInitialState(oldMessage);
        ageMessage(oldMessage.getGatewayMsgId());

        PostgresqlMessageStateStorage messageCleanup = new PostgresqlMessageStateStorage(dataSource);
        assertThat(messageCleanup.getState(oldMessage.getGatewayMsgId())).isEmpty();
    }

    private Optional<MessageState> resolveWhenReleased(String operatorMsgId, CountDownLatch ready,
                                                        CountDownLatch start) throws InterruptedException {
        ready.countDown();
        start.await();
        return storage.resolveAndRemoveDlr(operatorMsgId, MessageState.MessageStatus.DELIVERED);
    }

    private MessageState newState() {
        return new MessageState(UUID.randomUUID().toString(), "account", "system", "source", "destination",
                "https://example.test/dlr");
    }

    private int countCorrelations(String gatewayMsgId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT COUNT(*)
                     FROM sendium_dlr.operator_correlation
                     WHERE gateway_message_id = ?
                     """)) {
            statement.setObject(1, UUID.fromString(gatewayMsgId));
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1);
            }
        }
    }

    private void ageCorrelation(String operatorMsgId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE sendium_dlr.operator_correlation
                     SET created_at = CURRENT_TIMESTAMP - INTERVAL '4 days'
                     WHERE operator_message_id = ?
                     """)) {
            statement.setString(1, operatorMsgId);
            statement.executeUpdate();
        }
    }

    private void ageMessage(String gatewayMsgId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE sendium_dlr.tracked_message
                     SET created_at = CURRENT_TIMESTAMP - INTERVAL '8 days'
                     WHERE gateway_message_id = ?
                     """)) {
            statement.setObject(1, UUID.fromString(gatewayMsgId));
            statement.executeUpdate();
        }
    }
}
