package gr.cytech.sendium.core.worker;

import gr.cytech.sendium.core.message.StandardMessage;
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
import java.util.ArrayList;
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
class PostgresqlDlrStorageTest {
    private static final String MIGRATION_LOCATION = "classpath:db/sendium-dlr/postgresql";
    private static final PostgreSQLContainer POSTGRESQL = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("sendium")
            .withUsername("sendium")
            .withPassword("sendium-test");

    private static DataSource dataSource;

    private PostgresqlDlrStorage storage;

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
            statement.execute("TRUNCATE sendium_dlr.tracked_message, sendium_dlr.unpushed_dlr CASCADE");
        }
        storage = new PostgresqlDlrStorage(dataSource);
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
    void saveInitialStatesCommitsWholeBatch() {
        List<MessageState> states = List.of(newState(), newState(), newState());

        storage.saveInitialStates(states);

        for (MessageState state : states) {
            assertThat(storage.getState(state.getGatewayMsgId()))
                    .get()
                    .usingRecursiveComparison()
                    .isEqualTo(state);
        }
    }

    @Test
    void saveInitialStatesRollsBackWholeBatchOnCorrelationConflict() {
        MessageState owner = newState();
        owner.setOperatorMsgId("shared-operator");
        storage.saveInitialState(owner);
        MessageState innocent = newState();
        MessageState conflict = newState();
        conflict.setOperatorMsgId("shared-operator");

        assertThatThrownBy(() -> storage.saveInitialStates(List.of(innocent, conflict)))
                .isInstanceOf(DlrStorageException.class);

        assertThat(storage.getState(innocent.getGatewayMsgId())).isEmpty();
        assertThat(storage.getState(conflict.getGatewayMsgId())).isEmpty();
        assertThat(storage.getState(owner.getGatewayMsgId())).isPresent();
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
        PostgresqlDlrStorage noRetryStorage =
                new PostgresqlDlrStorage(dataSource, 1, 0);

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

        PostgresqlDlrStorage correlationCleanup = new PostgresqlDlrStorage(dataSource);
        assertThat(correlationCleanup.getState(correlationState.getGatewayMsgId())).isPresent();
        assertThat(countCorrelations(correlationState.getGatewayMsgId())).isZero();

        MessageState oldMessage = newState();
        storage.saveInitialState(oldMessage);
        ageMessage(oldMessage.getGatewayMsgId());

        PostgresqlDlrStorage messageCleanup = new PostgresqlDlrStorage(dataSource);
        assertThat(messageCleanup.getState(oldMessage.getGatewayMsgId())).isEmpty();
    }

    @Test
    void saveUnpushedDlrRoundTripsAllPersistedFields() {
        StandardMessage dlr = newDlr("account-1", "system-1");

        assertThat(storage.saveUnpushedDlr(dlr)).isTrue();

        List<StandardMessage> stored = storage.getUnpushedDlrs("system-1");
        assertThat(stored).singleElement().satisfies(actual -> {
            assertThat(actual.type).isEqualTo(StandardMessage.MSG_DLR);
            assertThat(actual.systemId).isEqualTo(dlr.systemId);
            assertThat(actual.owner_id).isEqualTo(dlr.owner_id);
            assertThat(actual.from).isEqualTo(dlr.from);
            assertThat(actual.to).isEqualTo(dlr.to);
            assertThat(actual.serial).isEqualTo(dlr.serial);
            assertThat(actual.msgId).isEqualTo(dlr.msgId);
            assertThat(actual.state).isEqualTo(dlr.state);
            assertThat(actual.errcode).isEqualTo(dlr.errcode);
            assertThat(actual.acked).isEqualTo(dlr.acked);
            assertThat(actual.priority).isEqualTo(dlr.priority);
            assertThat(actual.reassembledParts).containsExactlyElementsOf(dlr.reassembledParts);
        });
    }

    @Test
    void saveUnpushedDlrRejectsInvalidMessages() throws SQLException {
        StandardMessage wrongType = newDlr("account-1", "system-1");
        wrongType.type = StandardMessage.MSG_TEXT;
        StandardMessage blankSystem = newDlr("account-1", " ");

        assertThat(storage.saveUnpushedDlr(null)).isFalse();
        assertThat(storage.saveUnpushedDlr(wrongType)).isFalse();
        assertThat(storage.saveUnpushedDlr(blankSystem)).isFalse();
        assertThat(countUnpushedDlrs()).isZero();
    }

    @Test
    void saveUnpushedDlrOverwritesSameReplayKey() throws SQLException {
        StandardMessage initial = newDlr("account-1", "system-1");
        storage.saveUnpushedDlr(initial);

        StandardMessage replacement = newDlr("replacement-account", initial.systemId);
        replacement.serial = initial.serial;
        replacement.msgId = initial.msgId;
        replacement.state = initial.state;
        replacement.errcode = initial.errcode;
        replacement.priority = 9;
        replacement.reassembledParts = new ArrayList<>(List.of("replacement-part"));
        storage.saveUnpushedDlr(replacement);

        assertThat(countUnpushedDlrs()).isOne();
        assertThat(storage.getUnpushedDlrs(initial.systemId))
                .singleElement()
                .satisfies(actual -> {
                    assertThat(actual.owner_id).isEqualTo("replacement-account");
                    assertThat(actual.priority).isEqualTo(9);
                    assertThat(actual.reassembledParts).containsExactly("replacement-part");
                });
    }

    @Test
    void unpushedDlrsAreFilteredAndSurviveAdapterRecreation() {
        StandardMessage first = newDlr("account-1", "system-1");
        StandardMessage second = newDlr("account-2", "system-2");
        storage.saveUnpushedDlr(first);
        storage.saveUnpushedDlr(second);

        PostgresqlDlrStorage recreated = new PostgresqlDlrStorage(dataSource);

        assertThat(recreated.getUnpushedDlrs("system-1"))
                .extracting(message -> message.serial)
                .containsExactly(first.serial);
        assertThat(recreated.getUnpushedDlrs("system-2"))
                .extracting(message -> message.serial)
                .containsExactly(second.serial);
        assertThat(recreated.getUnpushedDlrs("missing-system")).isEmpty();
    }

    @Test
    void claimHidesReceiptUntilReleasedOrRemoved() {
        StandardMessage dlr = newDlr("account-1", "system-1");
        storage.saveUnpushedDlr(dlr);

        List<StandardMessage> firstClaim = storage.claimUnpushedDlrs(dlr.systemId);

        assertThat(firstClaim).hasSize(1);
        assertThat(storage.claimUnpushedDlrs(dlr.systemId)).isEmpty();
        assertThat(storage.getUnpushedDlrs(dlr.systemId)).hasSize(1);

        storage.releaseUnpushedDlrClaim(firstClaim.getFirst());
        List<StandardMessage> releasedClaim = storage.claimUnpushedDlrs(dlr.systemId);
        assertThat(releasedClaim).hasSize(1);
        assertThat(storage.removeUnpushedDlr(releasedClaim.getFirst())).isTrue();
        assertThat(storage.removeUnpushedDlr(releasedClaim.getFirst())).isFalse();
        assertThat(storage.getUnpushedDlrs(dlr.systemId)).isEmpty();
    }

    @Test
    void concurrentClaimsReturnReceiptOnlyOnce() throws Exception {
        StandardMessage dlr = newDlr("account-1", "system-1");
        storage.saveUnpushedDlr(dlr);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<List<StandardMessage>> first = executor.submit(
                    () -> claimWhenReleased(dlr.systemId, ready, start));
            Future<List<StandardMessage>> second = executor.submit(
                    () -> claimWhenReleased(dlr.systemId, ready, start));
            ready.await();
            start.countDown();

            assertThat(List.of(first.get(), second.get()).stream().filter(claim -> !claim.isEmpty()).count())
                    .isOne();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void expiryRemovesOldUnpushedDlrsAndTheirClaims() throws SQLException {
        StandardMessage dlr = newDlr("account-1", "system-1");
        storage.saveUnpushedDlr(dlr);
        storage = new PostgresqlDlrStorage(dataSource, 20, 200, 0);
        assertThat(storage.claimUnpushedDlrs(dlr.systemId)).hasSize(1);
        ageUnpushedDlr(dlr.serial);

        assertThat(storage.getUnpushedDlrs(dlr.systemId)).isEmpty();
        assertThat(countUnpushedDlrs()).isZero();

        assertThat(storage.saveUnpushedDlr(dlr)).isTrue();
        assertThat(storage.claimUnpushedDlrs(dlr.systemId)).hasSize(1);
        assertThat(storage.claimUnpushedDlrs(dlr.systemId)).isEmpty();
    }

    private Optional<MessageState> resolveWhenReleased(String operatorMsgId, CountDownLatch ready,
                                                        CountDownLatch start) throws InterruptedException {
        ready.countDown();
        start.await();
        return storage.resolveAndRemoveDlr(operatorMsgId, MessageState.MessageStatus.DELIVERED);
    }

    private List<StandardMessage> claimWhenReleased(String systemId, CountDownLatch ready,
                                                     CountDownLatch start) throws InterruptedException {
        ready.countDown();
        start.await();
        return storage.claimUnpushedDlrs(systemId);
    }

    private MessageState newState() {
        return new MessageState(UUID.randomUUID().toString(), "account", "system", "source", "destination",
                "https://example.test/dlr");
    }

    private StandardMessage newDlr(String accountId, String systemId) {
        StandardMessage dlr = new StandardMessage();
        dlr.type = StandardMessage.MSG_DLR;
        dlr.systemId = systemId;
        dlr.owner_id = accountId;
        dlr.from = "source";
        dlr.to = "destination";
        dlr.serial = UUID.randomUUID().toString();
        dlr.msgId = 42;
        dlr.state = 1;
        dlr.errcode = "000";
        dlr.acked = true;
        dlr.priority = 3;
        dlr.reassembledParts = new ArrayList<>(List.of("part-1", "part-2"));
        return dlr;
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

    private int countUnpushedDlrs() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM sendium_dlr.unpushed_dlr")) {
            resultSet.next();
            return resultSet.getInt(1);
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

    private void ageUnpushedDlr(String serial) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE sendium_dlr.unpushed_dlr
                     SET created_at = CURRENT_TIMESTAMP - INTERVAL '8 days'
                     WHERE serial = ?
                     """)) {
            statement.setString(1, serial);
            statement.executeUpdate();
        }
    }
}
