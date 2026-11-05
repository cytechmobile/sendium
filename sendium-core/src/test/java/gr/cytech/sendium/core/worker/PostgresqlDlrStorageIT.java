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
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
    void providerAcceptanceCreatesWaitingStateAndCorrelationTogether() throws SQLException {
        MessageState state = state(
                MessageState.DeliveryChannel.HTTP, "system", "https://example.test/dlr");
        state.setReassembledParts(List.of("part-1", "part-2"));
        state.setDlrMask(1);

        storage.recordProviderAccepted(state, PROVIDER, "provider-message-1");

        assertThat(storage.getState(state.getGatewayMsgId())).get().satisfies(actual -> {
            assertThat(actual.getStatus()).isEqualTo(MessageState.MessageStatus.SENT);
            assertThat(actual.getProviderName()).isEqualTo(PROVIDER);
            assertThat(actual.getProviderMessageId()).isEqualTo("provider-message-1");
            assertThat(actual.getDeliveryStatus()).isEqualTo(MessageState.DeliveryStatus.WAITING_PROVIDER);
            assertThat(actual.getReassembledParts()).containsExactly("part-1", "part-2");
            assertThat(actual.getDlrMask()).isEqualTo(1);
        });
        assertThat(countCorrelations(state.getGatewayMsgId())).isOne();
    }

    @Test
    void persistedMasksSelectOnlyRequestedTerminalCallbacks() throws Exception {
        for (int mask : new int[]{1, 2, 3}) {
            for (MessageState.MessageStatus outcome : List.of(MessageState.MessageStatus.DELIVERED,
                    MessageState.MessageStatus.FAILED)) {
                MessageState message = state(MessageState.DeliveryChannel.HTTP, "system", "https://example.test/dlr?id=%I&type=%d");
                message.setDlrMask(mask);
                String providerId = UUID.randomUUID().toString();
                storage.recordProviderAccepted(message, PROVIDER, providerId);
                storage.resolveDlr(PROVIDER, providerId, outcome,
                        outcome == MessageState.MessageStatus.DELIVERED
                                ? StandardMessage.DLR_STAT_DELIVRD : StandardMessage.DLR_STAT_FAILED, "0");
                var dlrService = new DlrService();
                dlrService.storage = new PostgresqlDlrStorage(dataSource);
                var client = mock(HttpClient.class);
                var response = mock(HttpResponse.class);
                int event = outcome == MessageState.MessageStatus.DELIVERED ? 1 : 2;
                if ((mask & event) != 0) {
                    when(response.statusCode()).thenReturn(204);
                    when(client.send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<Void>>any()))
                            .thenReturn(response);
                }
                new ForwardDlrService(dlrService, client).dispatchDueDeliveries();
                verify(client, times((mask & event) != 0 ? 1 : 0))
                        .send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<Void>>any());
                assertThat(storage.getState(message.getGatewayMsgId())).isEmpty();
                assertThat(countCorrelations(message.getGatewayMsgId())).isZero();
            }
        }
    }

    @Test
    void maskSurvivesBatchedAcceptanceTerminalResolutionAndStorageRecreation() {
        MessageState message = state(MessageState.DeliveryChannel.HTTP, "system", "https://example.test/dlr");
        message.setDlrMask(2);
        storage.applyProviderBatch(List.of(acceptedEvent(message, "mask-provider-id")));
        storage = new PostgresqlDlrStorage(dataSource);
        resolve("mask-provider-id");
        assertThat(storage.claimDueHttpDeliveries(10)).singleElement()
                .satisfies(actual -> assertThat(actual.getDlrMask()).isEqualTo(2));
    }

    @Test
    void additionalProviderAcceptancePreservesEarlierCorrelation() throws SQLException {
        MessageState state = state(
                MessageState.DeliveryChannel.SMPP, "system", null);

        storage.recordProviderAccepted(state, PROVIDER, "provider-message-1");
        storage.recordProviderAccepted(state, PROVIDER, "provider-message-2");

        assertThat(countCorrelations(state.getGatewayMsgId())).isEqualTo(2);
        assertThat(resolve("provider-message-1")).isPresent();
        assertThat(resolve("provider-message-2")).isEmpty();
    }

    @Test
    void providerBatchAppliesAcceptanceBeforeReceiptAndResolvesMultipartOnlyOnce() throws SQLException {
        MessageState message = state(MessageState.DeliveryChannel.HTTP, "system", "https://example.test/dlr");
        List<ProviderDlrEvent.Result> results = storage.applyProviderBatch(List.of(
                acceptedEvent(message, "part-a"), acceptedEvent(message, "part-b"),
                receiptEvent("part-a"), receiptEvent("part-b")));

        assertThat(results).hasSize(4).allSatisfy(result -> assertThat(result.failure()).isNull());
        assertThat(results.get(2).state()).isPresent();
        assertThat(results.get(3).state()).isEmpty();
        assertThat(countCorrelations(message.getGatewayMsgId())).isZero();
        assertThat(storage.getState(message.getGatewayMsgId())).get()
                .extracting(MessageState::getDeliveryStatus).isEqualTo(MessageState.DeliveryStatus.PENDING);
    }

    @Test
    void providerBatchRollsBackEveryEventWhenCorrelationWriteFails() throws SQLException {
        MessageState good = state(MessageState.DeliveryChannel.HTTP, "system", "https://example.test/good");
        MessageState bad = state(MessageState.DeliveryChannel.HTTP, "system", "https://example.test/bad");
        executeSql("""
                ALTER TABLE sendium_dlr.provider_correlation ADD CONSTRAINT reject_test_correlation
                CHECK (provider_message_id <> 'rejected-correlation')
                """);
        try {
            assertThatThrownBy(() -> storage.applyProviderBatch(List.of(acceptedEvent(good, "good"),
                    receiptEvent("good"), acceptedEvent(bad, "rejected-correlation"))))
                    .isInstanceOf(DlrStorageException.class);
            assertThat(storage.getState(good.getGatewayMsgId())).isEmpty();
            assertThat(storage.getState(bad.getGatewayMsgId())).isEmpty();
            assertThat(countCorrelations(good.getGatewayMsgId())).isZero();
        } finally {
            executeSql("ALTER TABLE sendium_dlr.provider_correlation DROP CONSTRAINT reject_test_correlation");
        }
    }

    @Test
    void providerBatchRetriesDeadlockFromTheBeginningAfterRollingBackPartialWrites() throws SQLException {
        AtomicInteger mutations = new AtomicInteger();
        DataSource injecting = (DataSource) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{DataSource.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("getConnection")) {
                        Connection delegate = dataSource.getConnection();
                        return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
                                (connection, operation, parameters) -> {
                                    if (operation.getName().equals("prepareStatement") &&
                                            ((String) parameters[0]).contains("WITH accepted AS") &&
                                            mutations.incrementAndGet() == 2) {
                                        throw new SQLException("Injected deadlock after first acceptance", "40P01");
                                    }
                                    try {
                                        return operation.invoke(delegate, parameters);
                                    } catch (InvocationTargetException e) {
                                        throw e.getCause();
                                    }
                                });
                    }
                    try {
                        return method.invoke(dataSource, arguments);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        var retrying = new PostgresqlDlrStorage(injecting);
        MessageState first = state(MessageState.DeliveryChannel.SMPP, "system", null);
        MessageState second = state(MessageState.DeliveryChannel.SMPP, "system", null);
        var results = retrying.applyProviderBatch(List.of(acceptedEvent(first, "a"), acceptedEvent(second, "b")));

        assertThat(results).hasSize(2).allSatisfy(result -> assertThat(result.failure()).isNull());
        assertThat(countCorrelations(first.getGatewayMsgId())).isOne();
        assertThat(countCorrelations(second.getGatewayMsgId())).isOne();
        assertThat(storage.getState(first.getGatewayMsgId())).isPresent();
        assertThat(storage.getState(second.getGatewayMsgId())).isPresent();
    }

    @Test
    void lateAcceptanceInBatchDoesNotOverwriteTerminalStateOrRejectOtherEvents() throws SQLException {
        MessageState terminal = state(MessageState.DeliveryChannel.HTTP, "system", "https://example.test/terminal");
        MessageState other = state(MessageState.DeliveryChannel.HTTP, "system", "https://example.test/other");
        var results = storage.applyProviderBatch(List.of(acceptedEvent(terminal, "first"), receiptEvent("first"),
                acceptedEvent(terminal, "late"), acceptedEvent(other, "other")));

        assertThat(results.get(2).failure()).isNotNull();
        assertThat(results.get(3).failure()).isNull();
        assertThat(countCorrelations(terminal.getGatewayMsgId())).isZero();
        assertThat(countCorrelations(other.getGatewayMsgId())).isOne();
        assertThat(storage.getState(terminal.getGatewayMsgId())).get()
                .extracting(MessageState::getDeliveryStatus).isEqualTo(MessageState.DeliveryStatus.PENDING);
    }

    @Test
    void providerBatchResolvesReusedIdToItsNewestOwner() throws SQLException {
        MessageState first = state(MessageState.DeliveryChannel.SMPP, "system", null);
        MessageState second = state(MessageState.DeliveryChannel.SMPP, "system", null);
        var results = storage.applyProviderBatch(List.of(acceptedEvent(first, "reused"),
                acceptedEvent(second, "reused"), receiptEvent("reused")));

        assertThat(results.get(2).state()).get().extracting(MessageState::getGatewayMsgId)
                .isEqualTo(second.getGatewayMsgId());
        assertThat(storage.getState(first.getGatewayMsgId())).get()
                .extracting(MessageState::getProviderMessageId).isNull();
        assertThat(countCorrelations(first.getGatewayMsgId())).isZero();
        assertThat(countCorrelations(second.getGatewayMsgId())).isZero();
    }

    @Test
    void providerBatchPersistsRejectionAndConsumesEarlierCorrelation() throws SQLException {
        MessageState message = state(MessageState.DeliveryChannel.SMPP, "system", null);
        var results = storage.applyProviderBatch(List.of(acceptedEvent(message, "accepted-part"),
                new ProviderDlrEvent.Rejected(message, PROVIDER, null, StandardMessage.DLR_STAT_FAILED, "22"),
                receiptEvent("accepted-part")));

        assertThat(results.get(1).state()).get().satisfies(rejected -> {
            assertThat(rejected.getDeliveryStatus()).isEqualTo(MessageState.DeliveryStatus.PENDING);
            assertThat(rejected.getStatus()).isEqualTo(MessageState.MessageStatus.FAILED);
            assertThat(rejected.getErrorCode()).isEqualTo("22");
        });
        assertThat(results.get(2).state()).isEmpty();
        assertThat(countCorrelations(message.getGatewayMsgId())).isZero();
    }

    @Test
    void providerBatchKeepsNamespacesSeparateAndSnapshotsReturnMetadata() throws SQLException {
        MessageState first = state(MessageState.DeliveryChannel.SMPP, "first-system", null);
        first.setReassembledParts(List.of("part-1", "part-2"));
        MessageState second = state(MessageState.DeliveryChannel.SMPP, "second-system", null);
        var accepted = acceptedEvent(first, "shared");
        first.setDeliveryChannel(MessageState.DeliveryChannel.NONE);
        first.setReassembledParts(List.of("changed"));
        var results = storage.applyProviderBatch(List.of(accepted,
                new ProviderDlrEvent.Accepted(second, "second-provider", "shared"), receiptEvent("shared")));

        assertThat(results.get(2).state()).get().satisfies(resolved -> {
            assertThat(resolved.getGatewayMsgId()).isEqualTo(first.getGatewayMsgId());
            assertThat(resolved.getDeliveryChannel()).isEqualTo(MessageState.DeliveryChannel.SMPP);
            assertThat(resolved.getReassembledParts()).containsExactly("part-1", "part-2");
        });
        assertThat(countCorrelations(second.getGatewayMsgId())).isOne();
    }

    @Test
    void concurrentBatchesWithOppositeInputOrderDoNotDeadlock() throws Exception {
        MessageState first = state(MessageState.DeliveryChannel.SMPP, "system", null);
        MessageState second = state(MessageState.DeliveryChannel.SMPP, "system", null);
        storage.recordProviderAccepted(first, PROVIDER, "a");
        storage.recordProviderAccepted(second, PROVIDER, "b");
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> a = executor.submit(() -> {
                start.await();
                return storage.applyProviderBatch(List.of(acceptedEvent(first, "a"), acceptedEvent(second, "b")));
            });
            Future<?> b = executor.submit(() -> {
                start.await();
                return storage.applyProviderBatch(List.of(acceptedEvent(second, "b"), acceptedEvent(first, "a")));
            });
            start.countDown();
            a.get(10, TimeUnit.SECONDS);
            b.get(10, TimeUnit.SECONDS);
            assertThat(countCorrelations(first.getGatewayMsgId())).isOne();
            assertThat(countCorrelations(second.getGatewayMsgId())).isOne();
        }
    }

    @Test
    void reusedProviderCorrelationMovesToNewestMessage() throws SQLException {
        MessageState first = state(MessageState.DeliveryChannel.SMPP, "system", null);
        MessageState newest = state(MessageState.DeliveryChannel.SMPP, "system", null);

        storage.recordProviderAccepted(first, PROVIDER, "reused-provider-message");
        storage.recordProviderAccepted(newest, PROVIDER, "reused-provider-message");

        assertThat(storage.getState(first.getGatewayMsgId())).get().satisfies(previous -> {
            assertThat(previous.getProviderName()).isNull();
            assertThat(previous.getProviderMessageId()).isNull();
        });
        assertThat(countCorrelations(first.getGatewayMsgId())).isZero();
        assertThat(countCorrelations(newest.getGatewayMsgId())).isOne();
        assertThat(resolve("reused-provider-message"))
                .get()
                .extracting(MessageState::getGatewayMsgId)
                .isEqualTo(newest.getGatewayMsgId());
    }

    @Test
    void failedCorrelationInsertRollsBackProviderAcceptance() throws SQLException {
        MessageState state = state(MessageState.DeliveryChannel.HTTP, "system", "https://example.test/dlr");
        executeSql("""
                ALTER TABLE sendium_dlr.provider_correlation ADD CONSTRAINT reject_test_correlation
                CHECK (provider_message_id <> 'rejected-correlation')
                """);
        try {
            assertThatThrownBy(() -> storage.recordProviderAccepted(state, PROVIDER, "rejected-correlation"))
                    .isInstanceOf(DlrStorageException.class);
            assertThat(storage.getState(state.getGatewayMsgId())).isEmpty();
            assertThat(countCorrelations(state.getGatewayMsgId())).isZero();
        } finally {
            executeSql("ALTER TABLE sendium_dlr.provider_correlation DROP CONSTRAINT reject_test_correlation");
        }
    }

    @Test
    void failedCorrelationDeletionRollsBackTerminalResolution() throws SQLException {
        MessageState state = state(MessageState.DeliveryChannel.HTTP, "system", "https://example.test/dlr");
        storage.recordProviderAccepted(state, PROVIDER, "delete-failure");
        executeSql("""
                CREATE FUNCTION sendium_dlr.reject_test_delete() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    RAISE EXCEPTION 'Injected correlation deletion failure';
                END
                $$
                """);
        try {
            executeSql("""
                    CREATE TRIGGER reject_test_delete BEFORE DELETE ON sendium_dlr.provider_correlation
                    FOR EACH ROW EXECUTE FUNCTION sendium_dlr.reject_test_delete()
                    """);
            assertThatThrownBy(() -> resolve("delete-failure")).isInstanceOf(DlrStorageException.class);
            assertThat(storage.getState(state.getGatewayMsgId())).get().satisfies(actual -> {
                assertThat(actual.getDeliveryStatus()).isEqualTo(MessageState.DeliveryStatus.WAITING_PROVIDER);
                assertThat(actual.getDlrState()).isNull();
            });
            assertThat(countCorrelations(state.getGatewayMsgId())).isOne();
        } finally {
            executeSql("DROP FUNCTION sendium_dlr.reject_test_delete() CASCADE");
        }
        assertThat(resolve("delete-failure")).isPresent();
        assertThat(countCorrelations(state.getGatewayMsgId())).isZero();
    }

    @Test
    void identicalMessageIdsFromDifferentProvidersResolveIndependently() throws SQLException {
        MessageState first = state(MessageState.DeliveryChannel.HTTP, "system", "https://example.test/first");
        MessageState second = state(MessageState.DeliveryChannel.HTTP, "system", "https://example.test/second");
        storage.recordProviderAccepted(first, PROVIDER, "shared-id");
        storage.recordProviderAccepted(second, "another-provider", "shared-id");

        assertThat(resolve("shared-id")).get().extracting(MessageState::getGatewayMsgId)
                .isEqualTo(first.getGatewayMsgId());
        assertThat(countCorrelations(second.getGatewayMsgId())).isOne();
        assertThat(storage.resolveDlr("another-provider", "shared-id", MessageState.MessageStatus.DELIVERED,
                StandardMessage.DLR_STAT_DELIVRD, "000"))
                .get().extracting(MessageState::getGatewayMsgId).isEqualTo(second.getGatewayMsgId());
    }

    @Test
    void providerRejectionCreatesPendingDeliveryWithoutCorrelation() throws SQLException {
        MessageState state = state(
                MessageState.DeliveryChannel.HTTP, "system", "https://example.test/dlr");

        Optional<MessageState> rejected = storage.recordProviderRejected(
                state, PROVIDER, "rejected-provider-message",
                StandardMessage.DLR_STAT_FAILED, "22");

        assertThat(rejected).get().satisfies(actual -> {
            assertThat(actual.getStatus()).isEqualTo(MessageState.MessageStatus.FAILED);
            assertThat(actual.getProviderName()).isEqualTo(PROVIDER);
            assertThat(actual.getProviderMessageId()).isEqualTo("rejected-provider-message");
            assertThat(actual.getDlrState()).isEqualTo(StandardMessage.DLR_STAT_FAILED);
            assertThat(actual.getErrorCode()).isEqualTo("22");
            assertThat(actual.getDeliveryStatus()).isEqualTo(MessageState.DeliveryStatus.PENDING);
            assertThat(actual.getResolvedAt()).isNotNull();
            assertThat(actual.getNextAttemptAt()).isNotNull();
        });
        assertThat(countCorrelations(state.getGatewayMsgId())).isZero();
    }

    @Test
    void providerRejectionWithoutMessageIdStoresNoProviderPair() throws SQLException {
        MessageState state = state(MessageState.DeliveryChannel.SMPP, "system", null);

        Optional<MessageState> rejected = storage.recordProviderRejected(
                state, PROVIDER, null, StandardMessage.DLR_STAT_FAILED, "22");

        assertThat(rejected).get().satisfies(actual -> {
            assertThat(actual.getProviderName()).isNull();
            assertThat(actual.getProviderMessageId()).isNull();
            assertThat(actual.getNextAttemptAt()).isNull();
        });
        assertThat(countCorrelations(state.getGatewayMsgId())).isZero();
    }

    @Test
    void providerRejectionConsumesExistingCorrelationsAndDoesNotRepeat() throws SQLException {
        MessageState state = state(MessageState.DeliveryChannel.SMPP, "system", null);
        storage.recordProviderAccepted(state, PROVIDER, "accepted-part");

        Optional<MessageState> rejected = storage.recordProviderRejected(
                state, PROVIDER, "rejected-part", StandardMessage.DLR_STAT_FAILED, "22");
        Optional<MessageState> duplicate = storage.recordProviderRejected(
                state, PROVIDER, "rejected-part", StandardMessage.DLR_STAT_FAILED, "22");

        assertThat(rejected).isPresent();
        assertThat(duplicate).isEmpty();
        assertThat(countCorrelations(state.getGatewayMsgId())).isZero();
    }

    @Test
    void terminalHttpStateIsRetainedWithExactOutcomeAndAllCorrelationsConsumed() throws SQLException {
        MessageState state = state(MessageState.DeliveryChannel.HTTP, "system", "https://example.test/dlr");
        storage.recordProviderAccepted(state, PROVIDER, "provider-message-1");
        storage.recordProviderAccepted(state, PROVIDER, "provider-message-2");

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
    void dueHttpDeliveriesAreClaimedInOrderAndHiddenAcrossAdapters() throws SQLException {
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

        assertThat(storage.claimDueHttpDeliveries(1))
                .extracting(MessageState::getGatewayMsgId)
                .containsExactly(first.getGatewayMsgId());
        PostgresqlDlrStorage otherAdapter = new PostgresqlDlrStorage(dataSource);
        assertThat(otherAdapter.claimDueHttpDeliveries(10))
                .extracting(MessageState::getGatewayMsgId)
                .containsExactly(later.getGatewayMsgId());
        assertThat(storage.getState(first.getGatewayMsgId()).orElseThrow().getDeliveryAttemptCount()).isOne();
        assertThat(storage.getState(later.getGatewayMsgId()).orElseThrow().getDeliveryAttemptCount()).isOne();
        assertThatThrownBy(() -> storage.claimDueHttpDeliveries(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void liveSmppClaimBlocksAnotherAdapterUntilTheLeaseExpires() throws SQLException {
        MessageState state = pendingSmpp("attempt-once");

        assertThat(storage.startDeliveryAttempt(
                state.getGatewayMsgId(), MessageState.DeliveryChannel.HTTP)).isEmpty();
        MessageState attempt = storage.startDeliveryAttempt(
                state.getGatewayMsgId(), MessageState.DeliveryChannel.SMPP).orElseThrow();
        PostgresqlDlrStorage otherAdapter = new PostgresqlDlrStorage(dataSource);

        assertThat(attempt.getDeliveryAttemptCount()).isOne();
        assertThat(attempt.getLastAttemptAt()).isNotNull();
        assertThat(otherAdapter.startDeliveryAttempt(
                state.getGatewayMsgId(), MessageState.DeliveryChannel.SMPP)).isEmpty();
        assertThat(storage.getState(state.getGatewayMsgId()).orElseThrow().getDeliveryAttemptCount()).isOne();

        setClaimedUntil(state.getGatewayMsgId(), "CURRENT_TIMESTAMP - INTERVAL '1 second'");
        MessageState retried = otherAdapter.startDeliveryAttempt(
                state.getGatewayMsgId(), MessageState.DeliveryChannel.SMPP).orElseThrow();

        assertThat(retried.getDeliveryAttemptCount()).isEqualTo(2);
    }

    @Test
    void staleAttemptCannotCompleteOrFailNewerAttempt() {
        MessageState state = pendingSmpp("stale-fence");
        MessageState first = storage.startDeliveryAttempt(
                state.getGatewayMsgId(), MessageState.DeliveryChannel.SMPP).orElseThrow();
        assertThat(storage.retryDelivery(state.getGatewayMsgId(), first.getDeliveryAttemptCount(),
                " first retry ", System.currentTimeMillis())).isTrue();
        PostgresqlDlrStorage otherAdapter = new PostgresqlDlrStorage(dataSource);
        MessageState second = otherAdapter.startDeliveryAttempt(
                state.getGatewayMsgId(), MessageState.DeliveryChannel.SMPP).orElseThrow();

        assertThat(storage.retryDelivery(state.getGatewayMsgId(), first.getDeliveryAttemptCount(),
                "stale", System.currentTimeMillis())).isFalse();
        assertThat(storage.completeDelivery(state.getGatewayMsgId(), first.getDeliveryAttemptCount())).isFalse();
        assertThat(storage.failDelivery(state.getGatewayMsgId(), first.getDeliveryAttemptCount(), "stale"))
                .isFalse();
        assertThat(storage.startDeliveryAttempt(
                state.getGatewayMsgId(), MessageState.DeliveryChannel.SMPP)).isEmpty();
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
        MessageState state = pendingSmpp("complete");
        MessageState attempt = storage.startDeliveryAttempt(
                state.getGatewayMsgId(), MessageState.DeliveryChannel.SMPP).orElseThrow();

        assertThat(storage.completeDelivery(state.getGatewayMsgId(), attempt.getDeliveryAttemptCount())).isTrue();
        assertThat(storage.getState(state.getGatewayMsgId())).isEmpty();
        assertThat(storage.completeDelivery(state.getGatewayMsgId(), attempt.getDeliveryAttemptCount())).isFalse();
    }

    @Test
    void retryStoresNormalizedResultAndSchedulesNextAttempt() {
        MessageState state = pendingHttp("retry");
        MessageState attempt = storage.claimDueHttpDeliveries(1).getFirst();
        long nextAttemptAt = System.currentTimeMillis() + Duration.ofHours(1).toMillis();

        assertThat(storage.retryDelivery(state.getGatewayMsgId(), attempt.getDeliveryAttemptCount(),
                "  timeout  ", nextAttemptAt)).isTrue();

        assertThat(storage.getState(state.getGatewayMsgId())).get().satisfies(actual -> {
            assertThat(actual.getDeliveryStatus()).isEqualTo(MessageState.DeliveryStatus.PENDING);
            assertThat(actual.getLastDeliveryResult()).isEqualTo("timeout");
            assertThat(actual.getNextAttemptAt()).isEqualTo(nextAttemptAt);
        });
        assertThat(storage.claimDueHttpDeliveries(10)).isEmpty();
    }

    @Test
    void invalidClaimedDeliveryFailsWithExpectedAttempt() {
        MessageState state = pendingHttp("invalid");
        MessageState attempt = storage.claimDueHttpDeliveries(1).getFirst();

        assertThat(storage.failInvalidDelivery(state.getGatewayMsgId(), attempt.getDeliveryAttemptCount(),
                "  missing URL  ")).isTrue();

        assertThat(storage.getState(state.getGatewayMsgId())).get().satisfies(actual -> {
            assertThat(actual.getDeliveryStatus()).isEqualTo(MessageState.DeliveryStatus.FAILED);
            assertThat(actual.getDeliveryAttemptCount()).isOne();
            assertThat(actual.getLastDeliveryResult()).isEqualTo("missing URL");
        });
    }

    @Test
    void liveHttpClaimBlocksAnotherAdapterUntilTheLeaseExpires() throws SQLException {
        MessageState state = pendingHttp("adapter-recreation");
        assertThat(storage.claimDueHttpDeliveries(1)).hasSize(1);

        PostgresqlDlrStorage recreated = new PostgresqlDlrStorage(dataSource);
        assertThat(recreated.claimDueHttpDeliveries(1)).isEmpty();

        setClaimedUntil(state.getGatewayMsgId(), "CURRENT_TIMESTAMP - INTERVAL '1 second'");
        MessageState retried = recreated.claimDueHttpDeliveries(1).getFirst();

        assertThat(retried.getDeliveryAttemptCount()).isEqualTo(2);
    }

    @Test
    void providerAcceptanceCannotOverwriteTerminalRow() throws SQLException {
        MessageState terminal = pendingHttp("terminal-guard");
        MessageState replacement = new MessageState(terminal.getGatewayMsgId(), "replacement-account",
                "replacement-system", "replacement-source", "replacement-destination",
                "https://example.test/replacement");
        replacement.setDeliveryChannel(MessageState.DeliveryChannel.HTTP);

        assertThatThrownBy(() -> storage.recordProviderAccepted(
                replacement, PROVIDER, "new-provider-message"))
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
        storage.recordProviderAccepted(oldWaiting, PROVIDER, "old-waiting");
        setCreatedAt(oldWaiting.getGatewayMsgId(), "CURRENT_TIMESTAMP - INTERVAL '8 days'");

        MessageState freshPendingWithOldCreation = pendingHttp("fresh-pending");
        setCreatedAt(freshPendingWithOldCreation.getGatewayMsgId(), "CURRENT_TIMESTAMP - INTERVAL '8 days'");

        MessageState oldPending = pendingHttp("old-pending");
        setResolvedAt(oldPending.getGatewayMsgId(), "CURRENT_TIMESTAMP - INTERVAL '8 days'");

        MessageState freshFailed = pendingHttp("fresh-failed");
        MessageState freshFailedAttempt = storage.claimDueHttpDeliveries(10).stream()
                .filter(state -> state.getGatewayMsgId().equals(freshFailed.getGatewayMsgId()))
                .findFirst().orElseThrow();
        storage.failInvalidDelivery(freshFailed.getGatewayMsgId(),
                freshFailedAttempt.getDeliveryAttemptCount(), "invalid");

        MessageState oldFailed = pendingHttp("old-failed");
        MessageState oldFailedAttempt = storage.claimDueHttpDeliveries(1).getFirst();
        assertThat(oldFailedAttempt.getGatewayMsgId()).isEqualTo(oldFailed.getGatewayMsgId());
        storage.failInvalidDelivery(oldFailed.getGatewayMsgId(),
                oldFailedAttempt.getDeliveryAttemptCount(), "invalid");
        setResolvedAt(oldFailed.getGatewayMsgId(), "CURRENT_TIMESTAMP - INTERVAL '8 days'");

        PostgresqlDlrStorage cleanup = new PostgresqlDlrStorage(dataSource, 0);
        cleanup.getState(freshPendingWithOldCreation.getGatewayMsgId());

        assertThat(cleanup.getState(oldWaiting.getGatewayMsgId())).isEmpty();
        assertThat(cleanup.getState(oldPending.getGatewayMsgId())).isEmpty();
        assertThat(cleanup.getState(oldFailed.getGatewayMsgId())).isEmpty();
        assertThat(cleanup.getState(freshPendingWithOldCreation.getGatewayMsgId())).isPresent();
        assertThat(cleanup.getState(freshFailed.getGatewayMsgId())).isPresent();
    }

    @Test
    void correlationRetentionMatchesWaitingMessageRetention() throws SQLException {
        MessageState state = state(MessageState.DeliveryChannel.HTTP, "system", "https://example.test/dlr");
        acceptProviderMessage(state, "old-correlation");
        setCorrelationCreatedAt("old-correlation", "CURRENT_TIMESTAMP - INTERVAL '4 days'");

        PostgresqlDlrStorage cleanup = new PostgresqlDlrStorage(dataSource, 0);
        assertThat(cleanup.getState(state.getGatewayMsgId())).isPresent();
        assertThat(countCorrelations(state.getGatewayMsgId())).isOne();

        setCorrelationCreatedAt("old-correlation", "CURRENT_TIMESTAMP - INTERVAL '8 days'");

        cleanup = new PostgresqlDlrStorage(dataSource, 0);
        assertThat(cleanup.getState(state.getGatewayMsgId())).isPresent();
        assertThat(countCorrelations(state.getGatewayMsgId())).isZero();
    }

    @Test
    void concurrentTerminalReceiptsResolveMessageOnlyOnce() throws Exception {
        MessageState state = state(MessageState.DeliveryChannel.HTTP, "system", "https://example.test/dlr");
        storage.recordProviderAccepted(state, PROVIDER, "provider-message-1");
        storage.recordProviderAccepted(state, PROVIDER, "provider-message-2");
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

    @Test
    void concurrentProviderAcceptancesRetainBothPartCorrelations() throws Exception {
        MessageState first = state(MessageState.DeliveryChannel.HTTP, "system", "https://example.test/dlr");
        MessageState second = new MessageState(first.getGatewayMsgId(), "account", "system",
                "source", "destination", "https://example.test/dlr");
        second.setDeliveryChannel(MessageState.DeliveryChannel.HTTP);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> firstAcceptance = executor.submit(() -> {
                ready.countDown();
                start.await();
                storage.recordProviderAccepted(first, PROVIDER, "first-part");
                return null;
            });
            Future<?> secondAcceptance = executor.submit(() -> {
                ready.countDown();
                start.await();
                storage.recordProviderAccepted(second, PROVIDER, "second-part");
                return null;
            });
            try {
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            } finally {
                start.countDown();
            }
            firstAcceptance.get(10, TimeUnit.SECONDS);
            secondAcceptance.get(10, TimeUnit.SECONDS);
            assertThat(countCorrelations(first.getGatewayMsgId())).isEqualTo(2);
            assertThat(resolve("first-part")).isPresent();
            assertThat(countCorrelations(first.getGatewayMsgId())).isZero();
        }
    }

    @Test
    void terminalResolutionConsumesCorrelationCommittedWhileWaitingForMessageLock() throws Exception {
        assertResolutionConsumesLaterPart(false);
    }

    @Test
    void batchedResolutionConsumesCorrelationCommittedWhileWaitingForMessageLock() throws Exception {
        assertResolutionConsumesLaterPart(true);
    }

    private void assertResolutionConsumesLaterPart(boolean batched) throws Exception {
        MessageState state = state(MessageState.DeliveryChannel.HTTP, "system", "https://example.test/dlr");
        storage.recordProviderAccepted(state, PROVIDER, "first-part");
        try (Connection accepting = dataSource.getConnection();
             ExecutorService executor = Executors.newSingleThreadExecutor()) {
            accepting.setAutoCommit(false);
            int acceptingPid;
            try (Statement statement = accepting.createStatement();
                 ResultSet resultSet = statement.executeQuery("SELECT pg_backend_pid()")) {
                resultSet.next();
                acceptingPid = resultSet.getInt(1);
            }
            try (PreparedStatement update = accepting.prepareStatement("""
                    UPDATE sendium_dlr.dlr_message SET provider_message_id = 'second-part'
                    WHERE gateway_message_id = ?
                    """);
                 PreparedStatement insert = accepting.prepareStatement("""
                         INSERT INTO sendium_dlr.provider_correlation
                             (provider_name, provider_message_id, gateway_message_id) VALUES (?, 'second-part', ?)
                         """)) {
                update.setObject(1, UUID.fromString(state.getGatewayMsgId()));
                update.executeUpdate();
                insert.setString(1, PROVIDER);
                insert.setObject(2, UUID.fromString(state.getGatewayMsgId()));
                insert.executeUpdate();

                Future<Optional<MessageState>> resolution = executor.submit(() -> batched
                        ? storage.applyProviderBatch(List.of(receiptEvent("first-part"))).getFirst().state()
                        : resolve("first-part"));
                try {
                    awaitBlockedBy(acceptingPid);
                } finally {
                    accepting.commit();
                }
                assertThat(resolution.get(10, TimeUnit.SECONDS)).isPresent();
                assertThat(countCorrelations(state.getGatewayMsgId())).isZero();
                assertThat(resolve("second-part")).isEmpty();
            }
        }
    }

    private ProviderDlrEvent.Accepted acceptedEvent(MessageState message, String providerId) {
        return new ProviderDlrEvent.Accepted(message, PROVIDER, providerId);
    }

    private ProviderDlrEvent.Receipt receiptEvent(String providerId) {
        return new ProviderDlrEvent.Receipt(PROVIDER, providerId, MessageState.MessageStatus.DELIVERED,
                StandardMessage.DLR_STAT_DELIVRD, "000");
    }

    private MessageState pendingHttp(String providerMessageId) {
        MessageState state = state(MessageState.DeliveryChannel.HTTP, "system", "https://example.test/dlr");
        saveResolve(state, providerMessageId);
        return state;
    }

    private MessageState pendingSmpp(String providerMessageId) {
        MessageState state = state(MessageState.DeliveryChannel.SMPP, "system", null);
        saveResolve(state, providerMessageId);
        return state;
    }

    private void saveResolve(MessageState state, String providerMessageId) {
        acceptProviderMessage(state, providerMessageId);
        resolve(providerMessageId).orElseThrow();
    }

    private void acceptProviderMessage(MessageState state, String providerMessageId) {
        storage.recordProviderAccepted(state, PROVIDER, providerMessageId);
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

    private void executeSql(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private void awaitBlockedBy(int pid) throws SQLException, InterruptedException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid)))
                     """)) {
            statement.setInt(1, pid);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < deadline) {
                try (ResultSet resultSet = statement.executeQuery()) {
                    resultSet.next();
                    if (resultSet.getBoolean(1)) {
                        return;
                    }
                }
                Thread.sleep(10);
            }
            throw new AssertionError("Resolution did not wait for the in-progress acceptance transaction");
        }
    }

    private void setCorrelationCreatedAt(String providerMessageId, String expression) throws SQLException {
        String sql = "UPDATE sendium_dlr.provider_correlation SET created_at = " + expression
                + " WHERE provider_name = ? AND provider_message_id = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, PROVIDER);
            statement.setString(2, providerMessageId);
            statement.executeUpdate();
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

    private void setClaimedUntil(String gatewayMsgId, String expression) throws SQLException {
        updateTimestamp(gatewayMsgId, "claimed_until", expression);
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
