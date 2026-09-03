package gr.cytech.sendium.core.worker;

import gr.cytech.sendium.core.message.StandardMessage;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PostgresqlDlrStorageIT {
    private static final String MIGRATION_LOCATION = "classpath:db/sendium-dlr/postgresql";
    private static final String PROVIDER = "provider-1";
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
            statement.execute("TRUNCATE sendium_dlr.dlr_message CASCADE");
        }
        storage = new PostgresqlDlrStorage(dataSource);
    }

    @Test
    void initialStateRoundTripsAndDefaultsToNoDelivery() {
        MessageState state = state(MessageState.DeliveryChannel.NONE, "system", null);
        state.setReassembledParts(List.of("part-1", "part-2"));

        storage.saveInitialState(state);

        assertThat(storage.getState(state.getGatewayMsgId()))
                .get()
                .usingRecursiveComparison()
                .isEqualTo(state);
        assertThat(state.getDeliveryStatus()).isEqualTo(MessageState.DeliveryStatus.WAITING_PROVIDER);
        assertThat(state.getDeliveryAttemptCount()).isZero();
    }

    @Test
    void terminalHttpStateIsRetainedWithExactOutcomeAndAllCorrelationsConsumed() throws SQLException {
        MessageState state = state(MessageState.DeliveryChannel.HTTP, "system", "https://example.test/dlr");
        storage.saveInitialState(state);
        storage.linkProviderMessageId(state.getGatewayMsgId(), PROVIDER, "provider-message-1");
        storage.linkProviderMessageId(state.getGatewayMsgId(), PROVIDER, "provider-message-2");

        Optional<MessageState> resolved = storage.resolveDlr(PROVIDER, "provider-message-1",
                MessageState.MessageStatus.FAILED, StandardMessage.DLR_STAT_REJECTD, "  exact-101  ");

        assertThat(resolved).get().satisfies(actual -> {
            assertThat(actual.getStatus()).isEqualTo(MessageState.MessageStatus.FAILED);
            assertThat(actual.getDlrState()).isEqualTo(StandardMessage.DLR_STAT_REJECTD);
            assertThat(actual.getErrorCode()).isEqualTo("  exact-101  ");
            assertThat(actual.getDeliveryStatus()).isEqualTo(MessageState.DeliveryStatus.PENDING);
            assertThat(actual.getResolvedAt()).isNotNull();
            assertThat(actual.getNextAttemptAt()).isNotNull();
        });
        assertThat(storage.getState(state.getGatewayMsgId())).isPresent();
        assertThat(countCorrelations(state.getGatewayMsgId())).isZero();
        assertThat(storage.resolveDlr(PROVIDER, "provider-message-2", MessageState.MessageStatus.DELIVERED,
                StandardMessage.DLR_STAT_DELIVRD, "000")).isEmpty();
    }

    @Test
    void terminalStateWithoutDeliveryChannelIsDeletedAfterResolution() throws SQLException {
        MessageState state = state(MessageState.DeliveryChannel.NONE, "system", null);
        saveAndLink(state, "provider-message");

        MessageState resolved = resolve("provider-message").orElseThrow();

        assertThat(resolved.getDlrState()).isEqualTo(StandardMessage.DLR_STAT_DELIVRD);
        assertThat(storage.getState(state.getGatewayMsgId())).isEmpty();
        assertThat(countCorrelations(state.getGatewayMsgId())).isZero();
    }

    @Test
    void pendingSmppDeliveriesAreFilteredBySystemAndOrderedOldestFirst() throws SQLException {
        MessageState newer = state(MessageState.DeliveryChannel.SMPP, "system-a", null);
        MessageState otherSystem = state(MessageState.DeliveryChannel.SMPP, "system-b", null);
        MessageState older = state(MessageState.DeliveryChannel.SMPP, "system-a", null);
        saveResolve(newer, "newer");
        saveResolve(otherSystem, "other");
        saveResolve(older, "older");
        setResolvedAt(older.getGatewayMsgId(), "CURRENT_TIMESTAMP - INTERVAL '2 hours'");
        setResolvedAt(newer.getGatewayMsgId(), "CURRENT_TIMESTAMP - INTERVAL '1 hour'");

        assertThat(storage.listPendingSmppDeliveries("system-a"))
                .extracting(MessageState::getGatewayMsgId)
                .containsExactly(older.getGatewayMsgId(), newer.getGatewayMsgId());
        assertThat(storage.listPendingSmppDeliveries("system-b"))
                .extracting(MessageState::getGatewayMsgId)
                .containsExactly(otherSystem.getGatewayMsgId());
        assertThat(storage.listPendingSmppDeliveries(" ")).isEmpty();
    }

    @Test
    void dueHttpDeliveriesAreFilteredOrderedAndLimited() throws SQLException {
        MessageState later = state(MessageState.DeliveryChannel.HTTP, "system", "https://example.test/later");
        MessageState first = state(MessageState.DeliveryChannel.HTTP, "system", "https://example.test/first");
        MessageState future = state(MessageState.DeliveryChannel.HTTP, "system", "https://example.test/future");
        MessageState smpp = state(MessageState.DeliveryChannel.SMPP, "system", null);
        saveResolve(later, "later");
        saveResolve(first, "first");
        saveResolve(future, "future");
        saveResolve(smpp, "smpp");
        setNextAttemptAt(first.getGatewayMsgId(), "CURRENT_TIMESTAMP - INTERVAL '2 hours'");
        setNextAttemptAt(later.getGatewayMsgId(), "CURRENT_TIMESTAMP - INTERVAL '1 hour'");
        setNextAttemptAt(future.getGatewayMsgId(), "CURRENT_TIMESTAMP + INTERVAL '1 hour'");

        assertThat(storage.listDueHttpDeliveries(1))
                .extracting(MessageState::getGatewayMsgId)
                .containsExactly(first.getGatewayMsgId());
        assertThat(storage.listDueHttpDeliveries(10))
                .extracting(MessageState::getGatewayMsgId)
                .containsExactly(first.getGatewayMsgId(), later.getGatewayMsgId());
        assertThatThrownBy(() -> storage.listDueHttpDeliveries(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void deliveryAttemptIncrementsOnceAndLocalGuardPreventsDuplicateStart() {
        MessageState state = pendingHttp("attempt-once");

        assertThat(storage.startDeliveryAttempt(
                state.getGatewayMsgId(), MessageState.DeliveryChannel.SMPP)).isEmpty();
        MessageState attempt = storage.startDeliveryAttempt(
                state.getGatewayMsgId(), MessageState.DeliveryChannel.HTTP).orElseThrow();

        assertThat(attempt.getDeliveryAttemptCount()).isOne();
        assertThat(attempt.getLastAttemptAt()).isNotNull();
        assertThat(storage.startDeliveryAttempt(
                state.getGatewayMsgId(), MessageState.DeliveryChannel.HTTP)).isEmpty();
        assertThat(storage.getState(state.getGatewayMsgId()).orElseThrow().getDeliveryAttemptCount()).isOne();
    }

    @Test
    void staleAttemptCannotCompleteOrFailNewerAttempt() {
        MessageState state = pendingHttp("stale-fence");
        MessageState first = storage.startDeliveryAttempt(
                state.getGatewayMsgId(), MessageState.DeliveryChannel.HTTP).orElseThrow();
        assertThat(storage.retryDelivery(state.getGatewayMsgId(), first.getDeliveryAttemptCount(),
                " first retry ", System.currentTimeMillis())).isTrue();
        MessageState second = storage.startDeliveryAttempt(
                state.getGatewayMsgId(), MessageState.DeliveryChannel.HTTP).orElseThrow();

        assertThat(storage.retryDelivery(state.getGatewayMsgId(), first.getDeliveryAttemptCount(),
                "stale", System.currentTimeMillis())).isFalse();
        assertThat(storage.completeDelivery(state.getGatewayMsgId(), first.getDeliveryAttemptCount())).isFalse();
        assertThat(storage.failDelivery(state.getGatewayMsgId(), first.getDeliveryAttemptCount(), "stale"))
                .isFalse();
        assertThat(storage.startDeliveryAttempt(
                state.getGatewayMsgId(), MessageState.DeliveryChannel.HTTP)).isEmpty();
        assertThat(storage.failDelivery(state.getGatewayMsgId(), second.getDeliveryAttemptCount(), " final "))
                .isTrue();
        assertThat(storage.getState(state.getGatewayMsgId())).get().satisfies(actual -> {
            assertThat(actual.getDeliveryStatus()).isEqualTo(MessageState.DeliveryStatus.FAILED);
            assertThat(actual.getLastDeliveryResult()).isEqualTo("final");
            assertThat(actual.getDeliveryAttemptCount()).isEqualTo(2);
        });
    }

    @Test
    void matchingCompletionDeletesPendingDelivery() {
        MessageState state = pendingHttp("complete");
        MessageState attempt = storage.startDeliveryAttempt(
                state.getGatewayMsgId(), MessageState.DeliveryChannel.HTTP).orElseThrow();

        assertThat(storage.completeDelivery(state.getGatewayMsgId(), attempt.getDeliveryAttemptCount())).isTrue();
        assertThat(storage.getState(state.getGatewayMsgId())).isEmpty();
        assertThat(storage.completeDelivery(state.getGatewayMsgId(), attempt.getDeliveryAttemptCount())).isFalse();
    }

    @Test
    void retryStoresNormalizedResultAndSchedulesNextAttempt() {
        MessageState state = pendingHttp("retry");
        MessageState attempt = storage.startDeliveryAttempt(
                state.getGatewayMsgId(), MessageState.DeliveryChannel.HTTP).orElseThrow();
        long nextAttemptAt = System.currentTimeMillis() + Duration.ofHours(1).toMillis();

        assertThat(storage.retryDelivery(state.getGatewayMsgId(), attempt.getDeliveryAttemptCount(),
                "  timeout  ", nextAttemptAt)).isTrue();

        assertThat(storage.getState(state.getGatewayMsgId())).get().satisfies(actual -> {
            assertThat(actual.getDeliveryStatus()).isEqualTo(MessageState.DeliveryStatus.PENDING);
            assertThat(actual.getLastDeliveryResult()).isEqualTo("timeout");
            assertThat(actual.getNextAttemptAt()).isEqualTo(nextAttemptAt);
        });
        assertThat(storage.listDueHttpDeliveries(10)).isEmpty();
    }

    @Test
    void invalidDeliveryFailsWithoutIncrementingAttempts() {
        MessageState state = pendingHttp("invalid");

        assertThat(storage.failInvalidDelivery(state.getGatewayMsgId(), "  missing URL  ")).isTrue();

        assertThat(storage.getState(state.getGatewayMsgId())).get().satisfies(actual -> {
            assertThat(actual.getDeliveryStatus()).isEqualTo(MessageState.DeliveryStatus.FAILED);
            assertThat(actual.getDeliveryAttemptCount()).isZero();
            assertThat(actual.getLastDeliveryResult()).isEqualTo("missing URL");
        });
    }

    @Test
    void adapterRecreationCanRetryAttemptThatWasActiveBeforeCrash() {
        MessageState state = pendingHttp("adapter-recreation");
        assertThat(storage.startDeliveryAttempt(
                state.getGatewayMsgId(), MessageState.DeliveryChannel.HTTP)).isPresent();

        PostgresqlDlrStorage recreated = new PostgresqlDlrStorage(dataSource);
        MessageState retried = recreated.startDeliveryAttempt(
                state.getGatewayMsgId(), MessageState.DeliveryChannel.HTTP).orElseThrow();

        assertThat(retried.getDeliveryAttemptCount()).isEqualTo(2);
    }

    @Test
    void initialSaveAndLinkCannotOverwriteOrRelinkTerminalRow() throws SQLException {
        MessageState terminal = pendingHttp("terminal-guard");
        MessageState replacement = new MessageState(terminal.getGatewayMsgId(), "replacement-account",
                "replacement-system", "replacement-source", "replacement-destination",
                "https://example.test/replacement");
        replacement.setDeliveryChannel(MessageState.DeliveryChannel.HTTP);

        storage.saveInitialState(replacement);
        PostgresqlDlrStorage noRetryStorage = new PostgresqlDlrStorage(dataSource, 1, 0);

        assertThatThrownBy(() -> noRetryStorage.linkProviderMessageId(
                terminal.getGatewayMsgId(), PROVIDER, "new-provider-message"))
                .isInstanceOf(DlrStorageException.class);
        assertThat(storage.getState(terminal.getGatewayMsgId())).get().satisfies(actual -> {
            assertThat(actual.getAccountId()).isEqualTo(terminal.getAccountId());
            assertThat(actual.getDlrState()).isEqualTo(StandardMessage.DLR_STAT_DELIVRD);
            assertThat(actual.getDeliveryStatus()).isEqualTo(MessageState.DeliveryStatus.PENDING);
        });
        assertThat(countCorrelations(terminal.getGatewayMsgId())).isZero();
    }

    @Test
    void retentionUsesCreatedAtWhileWaitingAndResolvedAtAfterResolution() throws SQLException {
        MessageState oldWaiting = state(MessageState.DeliveryChannel.HTTP, "system", "https://example.test/waiting");
        storage.saveInitialState(oldWaiting);
        setCreatedAt(oldWaiting.getGatewayMsgId(), "CURRENT_TIMESTAMP - INTERVAL '8 days'");

        MessageState freshPendingWithOldCreation = pendingHttp("fresh-pending");
        setCreatedAt(freshPendingWithOldCreation.getGatewayMsgId(), "CURRENT_TIMESTAMP - INTERVAL '8 days'");

        MessageState oldPending = pendingHttp("old-pending");
        setResolvedAt(oldPending.getGatewayMsgId(), "CURRENT_TIMESTAMP - INTERVAL '8 days'");

        MessageState freshFailed = pendingHttp("fresh-failed");
        storage.failInvalidDelivery(freshFailed.getGatewayMsgId(), "invalid");

        MessageState oldFailed = pendingHttp("old-failed");
        storage.failInvalidDelivery(oldFailed.getGatewayMsgId(), "invalid");
        setResolvedAt(oldFailed.getGatewayMsgId(), "CURRENT_TIMESTAMP - INTERVAL '8 days'");

        PostgresqlDlrStorage cleanup = new PostgresqlDlrStorage(dataSource, 1, 0, 0);
        cleanup.getState(freshPendingWithOldCreation.getGatewayMsgId());

        assertThat(cleanup.getState(oldWaiting.getGatewayMsgId())).isEmpty();
        assertThat(cleanup.getState(oldPending.getGatewayMsgId())).isEmpty();
        assertThat(cleanup.getState(oldFailed.getGatewayMsgId())).isEmpty();
        assertThat(cleanup.getState(freshPendingWithOldCreation.getGatewayMsgId())).isPresent();
        assertThat(cleanup.getState(freshFailed.getGatewayMsgId())).isPresent();
    }

    @Test
    void correlationRetentionRemainsThreeDays() throws SQLException {
        MessageState state = state(MessageState.DeliveryChannel.HTTP, "system", "https://example.test/dlr");
        saveAndLink(state, "old-correlation");
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE sendium_dlr.provider_correlation
                     SET created_at = CURRENT_TIMESTAMP - INTERVAL '4 days'
                     WHERE provider_name = ? AND provider_message_id = ?
                     """)) {
            statement.setString(1, PROVIDER);
            statement.setString(2, "old-correlation");
            statement.executeUpdate();
        }

        PostgresqlDlrStorage cleanup = new PostgresqlDlrStorage(dataSource, 1, 0, 0);
        assertThat(cleanup.getState(state.getGatewayMsgId())).isPresent();
        assertThat(countCorrelations(state.getGatewayMsgId())).isZero();
    }

    @Test
    void concurrentTerminalReceiptsResolveMessageOnlyOnce() throws Exception {
        MessageState state = state(MessageState.DeliveryChannel.HTTP, "system", "https://example.test/dlr");
        storage.saveInitialState(state);
        storage.linkProviderMessageId(state.getGatewayMsgId(), PROVIDER, "provider-message-1");
        storage.linkProviderMessageId(state.getGatewayMsgId(), PROVIDER, "provider-message-2");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<Optional<MessageState>> first = executor.submit(
                    () -> resolveWhenReleased("provider-message-1", ready, start));
            Future<Optional<MessageState>> second = executor.submit(
                    () -> resolveWhenReleased("provider-message-2", ready, start));
            ready.await();
            start.countDown();

            assertThat(List.of(first.get(), second.get()).stream().filter(Optional::isPresent).count()).isOne();
        } finally {
            executor.shutdownNow();
        }
    }

    private MessageState pendingHttp(String providerMessageId) {
        MessageState state = state(MessageState.DeliveryChannel.HTTP, "system", "https://example.test/dlr");
        saveResolve(state, providerMessageId);
        return state;
    }

    private void saveResolve(MessageState state, String providerMessageId) {
        saveAndLink(state, providerMessageId);
        resolve(providerMessageId).orElseThrow();
    }

    private void saveAndLink(MessageState state, String providerMessageId) {
        storage.saveInitialState(state);
        storage.linkProviderMessageId(state.getGatewayMsgId(), PROVIDER, providerMessageId);
    }

    private Optional<MessageState> resolve(String providerMessageId) {
        return storage.resolveDlr(PROVIDER, providerMessageId, MessageState.MessageStatus.DELIVERED,
                StandardMessage.DLR_STAT_DELIVRD, "000");
    }

    private Optional<MessageState> resolveWhenReleased(String providerMessageId, CountDownLatch ready,
                                                       CountDownLatch start) throws InterruptedException {
        ready.countDown();
        start.await();
        return storage.resolveDlr(PROVIDER, providerMessageId, MessageState.MessageStatus.DELIVERED,
                StandardMessage.DLR_STAT_DELIVRD, "000");
    }

    private MessageState state(MessageState.DeliveryChannel channel, String systemId, String callbackUrl) {
        MessageState state = new MessageState(UUID.randomUUID().toString(), "account", systemId,
                "source", "destination", callbackUrl);
        state.setDeliveryChannel(channel);
        return state;
    }

    private int countCorrelations(String gatewayMsgId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT COUNT(*) FROM sendium_dlr.provider_correlation WHERE gateway_message_id = ?
                     """)) {
            statement.setObject(1, UUID.fromString(gatewayMsgId));
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1);
            }
        }
    }

    private void setCreatedAt(String gatewayMsgId, String expression) throws SQLException {
        updateTimestamp(gatewayMsgId, "created_at", expression);
    }

    private void setResolvedAt(String gatewayMsgId, String expression) throws SQLException {
        updateTimestamp(gatewayMsgId, "resolved_at", expression);
    }

    private void setNextAttemptAt(String gatewayMsgId, String expression) throws SQLException {
        updateTimestamp(gatewayMsgId, "next_attempt_at", expression);
    }

    private void updateTimestamp(String gatewayMsgId, String column, String expression) throws SQLException {
        String sql = "UPDATE sendium_dlr.dlr_message SET " + column + " = " + expression
                + " WHERE gateway_message_id = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, UUID.fromString(gatewayMsgId));
            statement.executeUpdate();
        }
    }
}
