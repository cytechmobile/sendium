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
            statement.execute("TRUNCATE sendium_dlr.tracked_message, sendium_dlr.unpushed_dlr CASCADE");
        }
        storage = new PostgresqlDlrStorage(dataSource);
    }

    @Test
    void saveInitialStateRoundTripsAllFields() {
        MessageState state = newState();
        state.setProviderName(PROVIDER);
        state.setProviderMessageId("provider-message-initial");
        state.setReassembledParts(List.of("part-1", "part-2"));

        storage.saveInitialState(state);

        assertThat(storage.getState(state.getGatewayMsgId()))
                .get()
                .usingRecursiveComparison()
                .isEqualTo(state);
        assertThat(storage.resolveAndRemoveDlr(
                PROVIDER, "provider-message-initial", MessageState.MessageStatus.DELIVERED))
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
    void saveInitialStatesRebindsCorrelationToNewestBatchMessage() throws SQLException {
        MessageState owner = newState();
        owner.setProviderName(PROVIDER);
        owner.setProviderMessageId("shared-provider-message");
        storage.saveInitialState(owner);
        MessageState innocent = newState();
        MessageState conflict = newState();
        conflict.setProviderName(PROVIDER);
        conflict.setProviderMessageId("shared-provider-message");

        storage.saveInitialStates(List.of(innocent, conflict));

        assertThat(storage.getState(innocent.getGatewayMsgId())).isPresent();
        assertThat(storage.getState(conflict.getGatewayMsgId())).isPresent();
        assertThat(storage.getState(owner.getGatewayMsgId()).orElseThrow().getProviderMessageId()).isNull();
        assertThat(countCorrelations(owner.getGatewayMsgId())).isZero();
        assertThat(countCorrelations(conflict.getGatewayMsgId())).isOne();
        assertThat(storage.resolveAndRemoveDlr(
                PROVIDER, "shared-provider-message", MessageState.MessageStatus.DELIVERED))
                .get()
                .extracting(MessageState::getGatewayMsgId)
                .isEqualTo(conflict.getGatewayMsgId());
    }

    @Test
    void saveInitialStatesUsesFinalStateForDuplicateGatewayMessage() throws SQLException {
        MessageState correlated = newState();
        correlated.setProviderName(PROVIDER);
        correlated.setProviderMessageId("superseded-provider-message");
        MessageState replacement = new MessageState(correlated.getGatewayMsgId(), "replacement-account",
                "replacement-system", "replacement-source", "replacement-destination", null);

        storage.saveInitialStates(List.of(correlated, replacement));

        assertThat(storage.getState(replacement.getGatewayMsgId()))
                .get()
                .usingRecursiveComparison()
                .isEqualTo(replacement);
        assertThat(countCorrelations(replacement.getGatewayMsgId())).isZero();
        assertThat(storage.resolveAndRemoveDlr(
                PROVIDER, "superseded-provider-message", MessageState.MessageStatus.DELIVERED)).isEmpty();
    }

    @Test
    void trackedStateAndCorrelationSurviveAdapterRecreation() {
        MessageState state = newState();
        storage.saveInitialState(state);
        storage.linkProviderMessageId(state.getGatewayMsgId(), PROVIDER, "provider-message-after-restart");

        PostgresqlDlrStorage recreated = new PostgresqlDlrStorage(dataSource);

        assertThat(recreated.getState(state.getGatewayMsgId()))
                .get()
                .extracting(MessageState::getProviderMessageId, MessageState::getStatus)
                .containsExactly("provider-message-after-restart", MessageState.MessageStatus.SENT);
        assertThat(recreated.resolveAndRemoveDlr(
                PROVIDER, "provider-message-after-restart", MessageState.MessageStatus.DELIVERED))
                .get()
                .extracting(MessageState::getGatewayMsgId, MessageState::getStatus)
                .containsExactly(state.getGatewayMsgId(), MessageState.MessageStatus.DELIVERED);
    }

    @Test
    void saveInitialStateOverwritesExistingState() throws SQLException {
        MessageState initial = newState();
        storage.saveInitialState(initial);
        storage.linkProviderMessageId(initial.getGatewayMsgId(), PROVIDER, "provider-message-old");

        MessageState replacement = new MessageState(initial.getGatewayMsgId(), "replacement-account",
                "replacement-system", "replacement-source", "replacement-destination", null);
        replacement.setStatus(MessageState.MessageStatus.FAILED);
        storage.saveInitialState(replacement);

        assertThat(storage.getState(initial.getGatewayMsgId()))
                .get()
                .usingRecursiveComparison()
                .isEqualTo(replacement);
        assertThat(countCorrelations(initial.getGatewayMsgId())).isZero();
        assertThat(storage.resolveAndRemoveDlr(
                PROVIDER, "provider-message-old", MessageState.MessageStatus.DELIVERED))
                .isEmpty();
    }

    @Test
    void saveInitialStateRebindsCorrelationOwnedByAnotherMessage() throws SQLException {
        MessageState owner = newState();
        storage.saveInitialState(owner);
        storage.linkProviderMessageId(owner.getGatewayMsgId(), PROVIDER, "shared-provider-message");

        MessageState target = newState();
        storage.saveInitialState(target);
        storage.linkProviderMessageId(target.getGatewayMsgId(), PROVIDER, "target-provider-message");
        MessageState replacement = new MessageState(target.getGatewayMsgId(), "replacement-account",
                "replacement-system", "replacement-source", "replacement-destination", null);
        replacement.setProviderName(PROVIDER);
        replacement.setProviderMessageId("shared-provider-message");
        storage.saveInitialState(replacement);

        assertThat(storage.getState(target.getGatewayMsgId()))
                .get()
                .usingRecursiveComparison()
                .isEqualTo(replacement);
        assertThat(storage.getState(owner.getGatewayMsgId()).orElseThrow().getProviderMessageId())
                .isNull();
        assertThat(countCorrelations(target.getGatewayMsgId())).isOne();
        assertThat(countCorrelations(owner.getGatewayMsgId())).isZero();

        MessageState newConflict = newState();
        newConflict.setProviderName(PROVIDER);
        newConflict.setProviderMessageId("shared-provider-message");
        storage.saveInitialState(newConflict);

        assertThat(storage.getState(target.getGatewayMsgId()).orElseThrow().getProviderMessageId()).isNull();
        assertThat(countCorrelations(target.getGatewayMsgId())).isZero();
        assertThat(countCorrelations(newConflict.getGatewayMsgId())).isOne();
    }

    @Test
    void linkProviderMessageIdUpdatesStateAndKeepsMultipleCorrelations() throws SQLException {
        MessageState state = newState();
        storage.saveInitialState(state);

        storage.linkProviderMessageId(state.getGatewayMsgId(), PROVIDER, "provider-message-1");
        storage.linkProviderMessageId(state.getGatewayMsgId(), PROVIDER, "provider-message-2");

        MessageState linked = storage.getState(state.getGatewayMsgId()).orElseThrow();
        assertThat(linked.getStatus()).isEqualTo(MessageState.MessageStatus.SENT);
        assertThat(linked.getProviderMessageId()).isEqualTo("provider-message-2");
        assertThat(countCorrelations(state.getGatewayMsgId())).isEqualTo(2);
    }

    @Test
    void linkProviderMessageIdRejectsInvalidCorrelation() {
        MessageState state = newState();
        storage.saveInitialState(state);

        assertThatThrownBy(() -> storage.linkProviderMessageId(state.getGatewayMsgId(), PROVIDER, null))
                .isInstanceOf(IllegalArgumentException.class);

        MessageState unchanged = storage.getState(state.getGatewayMsgId()).orElseThrow();
        assertThat(unchanged.getStatus()).isEqualTo(MessageState.MessageStatus.ACCEPTED);
        assertThat(unchanged.getProviderMessageId()).isNull();
    }

    @Test
    void linkProviderMessageIdRebindsCorrelationToNewestMessage() throws SQLException {
        MessageState first = new MessageState(UUID.randomUUID().toString(), "first-account", "first-system",
                "first-source", "first-destination", null);
        MessageState second = new MessageState(UUID.randomUUID().toString(), "second-account", "second-system",
                "second-source", "second-destination", null);
        storage.saveInitialState(first);
        storage.saveInitialState(second);
        storage.linkProviderMessageId(first.getGatewayMsgId(), PROVIDER, "shared-provider-message");

        storage.linkProviderMessageId(second.getGatewayMsgId(), PROVIDER, "shared-provider-message");

        assertThat(storage.getState(first.getGatewayMsgId()).orElseThrow().getProviderMessageId())
                .isNull();
        MessageState linked = storage.getState(second.getGatewayMsgId()).orElseThrow();
        assertThat(linked.getStatus()).isEqualTo(MessageState.MessageStatus.SENT);
        assertThat(linked.getProviderMessageId()).isEqualTo("shared-provider-message");
        assertThat(countCorrelations(first.getGatewayMsgId())).isZero();
        assertThat(countCorrelations(second.getGatewayMsgId())).isOne();

        assertThat(storage.resolveAndRemoveDlr(
                PROVIDER, "shared-provider-message", MessageState.MessageStatus.DELIVERED))
                .get()
                .extracting(MessageState::getGatewayMsgId, MessageState::getAccountId)
                .containsExactly(second.getGatewayMsgId(), "second-account");
        assertThat(storage.getState(first.getGatewayMsgId())).isPresent();
    }

    @Test
    void sameMessageIdFromDifferentProvidersResolvesIndependently() {
        MessageState first = newState();
        MessageState second = newState();
        storage.saveInitialStates(List.of(first, second));

        storage.linkProviderMessageId(first.getGatewayMsgId(), "provider-a", "shared-provider-message");
        storage.linkProviderMessageId(second.getGatewayMsgId(), "provider-b", "shared-provider-message");

        assertThat(storage.resolveAndRemoveDlr(
                "provider-a", "shared-provider-message", MessageState.MessageStatus.DELIVERED))
                .get()
                .extracting(MessageState::getGatewayMsgId, MessageState::getProviderName,
                        MessageState::getProviderMessageId)
                .containsExactly(first.getGatewayMsgId(), "provider-a", "shared-provider-message");
        assertThat(storage.resolveAndRemoveDlr(
                "provider-b", "shared-provider-message", MessageState.MessageStatus.DELIVERED))
                .get()
                .extracting(MessageState::getGatewayMsgId, MessageState::getProviderName,
                        MessageState::getProviderMessageId)
                .containsExactly(second.getGatewayMsgId(), "provider-b", "shared-provider-message");
    }

    @Test
    void concurrentProviderMessageIdRebindLeavesOneConsistentOwner() throws Exception {
        MessageState first = newState();
        MessageState second = newState();
        storage.saveInitialState(first);
        storage.saveInitialState(second);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> firstLink = executor.submit(() -> {
                linkWhenReleased(first.getGatewayMsgId(), "shared-provider-message", ready, start);
                return null;
            });
            Future<?> secondLink = executor.submit(() -> {
                linkWhenReleased(second.getGatewayMsgId(), "shared-provider-message", ready, start);
                return null;
            });
            ready.await();
            start.countDown();
            firstLink.get();
            secondLink.get();

            MessageState firstAfter = storage.getState(first.getGatewayMsgId()).orElseThrow();
            MessageState secondAfter = storage.getState(second.getGatewayMsgId()).orElseThrow();
            assertThat(List.of(firstAfter, secondAfter).stream()
                    .filter(state -> "shared-provider-message".equals(state.getProviderMessageId())))
                    .hasSize(1);
            assertThat(countCorrelations(first.getGatewayMsgId()) + countCorrelations(second.getGatewayMsgId()))
                    .isOne();
            String owner = storage.resolveAndRemoveDlr(
                    PROVIDER, "shared-provider-message", MessageState.MessageStatus.DELIVERED)
                    .orElseThrow()
                    .getGatewayMsgId();
            assertThat(owner).isIn(first.getGatewayMsgId(), second.getGatewayMsgId());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentRebindAndResolveCompleteWithoutDeadlock() throws Exception {
        MessageState first = newState();
        MessageState second = newState();
        storage.saveInitialStates(List.of(first, second));
        storage.linkProviderMessageId(first.getGatewayMsgId(), PROVIDER, "shared-provider-message");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> rebind = executor.submit(() -> {
                linkWhenReleased(second.getGatewayMsgId(), "shared-provider-message", ready, start);
                return null;
            });
            Future<Optional<MessageState>> resolve = executor.submit(
                    () -> resolveWhenReleased("shared-provider-message", ready, start));
            ready.await();
            start.countDown();

            rebind.get();
            assertThat(resolve.get()).isPresent();
            assertThat(countCorrelations(first.getGatewayMsgId())
                    + countCorrelations(second.getGatewayMsgId())).isLessThanOrEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentCrossedRebindsLockMessagesInConsistentOrder() throws Exception {
        MessageState first = newState();
        MessageState second = newState();
        storage.saveInitialState(first);
        storage.saveInitialState(second);
        storage.linkProviderMessageId(first.getGatewayMsgId(), PROVIDER, "provider-message-2");
        storage.linkProviderMessageId(second.getGatewayMsgId(), PROVIDER, "provider-message-1");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> firstLink = executor.submit(() -> {
                linkWhenReleased(first.getGatewayMsgId(), "provider-message-1", ready, start);
                return null;
            });
            Future<?> secondLink = executor.submit(() -> {
                linkWhenReleased(second.getGatewayMsgId(), "provider-message-2", ready, start);
                return null;
            });
            ready.await();
            start.countDown();
            firstLink.get();
            secondLink.get();

            assertThat(storage.getState(first.getGatewayMsgId()).orElseThrow().getProviderMessageId())
                    .isEqualTo("provider-message-1");
            assertThat(storage.getState(second.getGatewayMsgId()).orElseThrow().getProviderMessageId())
                    .isEqualTo("provider-message-2");
            assertThat(countCorrelations(first.getGatewayMsgId())).isOne();
            assertThat(countCorrelations(second.getGatewayMsgId())).isOne();
            assertThat(storage.resolveAndRemoveDlr(
                    PROVIDER, "provider-message-1", MessageState.MessageStatus.DELIVERED))
                    .get()
                    .extracting(MessageState::getGatewayMsgId)
                    .isEqualTo(first.getGatewayMsgId());
            assertThat(storage.resolveAndRemoveDlr(
                    PROVIDER, "provider-message-2", MessageState.MessageStatus.DELIVERED))
                    .get()
                    .extracting(MessageState::getGatewayMsgId)
                    .isEqualTo(second.getGatewayMsgId());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void linkProviderMessageIdFailsWhenGatewayStateDoesNotAppear() {
        PostgresqlDlrStorage noRetryStorage =
                new PostgresqlDlrStorage(dataSource, 1, 0);

        assertThatThrownBy(() -> noRetryStorage.linkProviderMessageId(
                UUID.randomUUID().toString(), PROVIDER, "provider-message"))
                .isInstanceOf(DlrStorageException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void resolveAndRemoveDlrReturnsUpdatedStateAndDeletesAllCorrelations() throws SQLException {
        MessageState state = newState();
        storage.saveInitialState(state);
        storage.linkProviderMessageId(state.getGatewayMsgId(), PROVIDER, "provider-message-1");
        storage.linkProviderMessageId(state.getGatewayMsgId(), PROVIDER, "provider-message-2");
        long beforeResolve = System.currentTimeMillis();

        Optional<MessageState> resolved = storage.resolveAndRemoveDlr(
                PROVIDER, "provider-message-1", MessageState.MessageStatus.DELIVERED);

        assertThat(resolved).isPresent();
        assertThat(resolved.orElseThrow().getStatus()).isEqualTo(MessageState.MessageStatus.DELIVERED);
        assertThat(resolved.orElseThrow().getProviderMessageId()).isEqualTo("provider-message-1");
        assertThat(resolved.orElseThrow().getTimestamp()).isGreaterThanOrEqualTo(beforeResolve);
        assertThat(storage.getState(state.getGatewayMsgId())).isEmpty();
        assertThat(countCorrelations(state.getGatewayMsgId())).isZero();
    }

    @Test
    void concurrentResolveAcrossCorrelationsReturnsStateOnlyOnce() throws Exception {
        MessageState state = newState();
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
        storage.linkProviderMessageId(correlationState.getGatewayMsgId(), PROVIDER, "old-correlation");
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
        assertThat(recreated.claimUnpushedDlrs("system-1"))
                .extracting(message -> message.serial)
                .containsExactly(first.serial);
        assertThat(recreated.claimUnpushedDlrs("system-1")).isEmpty();
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
    void staleClaimCannotRemoveOrReleaseReplacementGeneration() {
        StandardMessage original = newDlr("account-1", "system-1");
        storage.saveUnpushedDlr(original);
        StandardMessage staleClaim = storage.claimUnpushedDlrs(original.systemId).getFirst();
        StandardMessage replacement = newDlr("account-2", original.systemId);
        replacement.serial = original.serial;
        replacement.msgId = original.msgId;
        replacement.state = original.state;
        replacement.errcode = original.errcode;
        storage.saveUnpushedDlr(replacement);

        assertThat(storage.removeUnpushedDlr(staleClaim)).isFalse();
        List<StandardMessage> replacementClaim = storage.claimUnpushedDlrs(original.systemId);
        assertThat(replacementClaim).hasSize(1);
        assertThat(replacementClaim.getFirst().owner_id).isEqualTo("account-2");

        storage.releaseUnpushedDlrClaim(staleClaim);
        assertThat(storage.claimUnpushedDlrs(original.systemId)).isEmpty();
        storage.releaseUnpushedDlrClaim(replacementClaim.getFirst());
        assertThat(storage.claimUnpushedDlrs(original.systemId)).hasSize(1);
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

    private Optional<MessageState> resolveWhenReleased(String providerMessageId, CountDownLatch ready,
                                                         CountDownLatch start) throws InterruptedException {
        ready.countDown();
        start.await();
        return storage.resolveAndRemoveDlr(PROVIDER, providerMessageId, MessageState.MessageStatus.DELIVERED);
    }

    private void linkWhenReleased(String gatewayMsgId, String providerMessageId, CountDownLatch ready,
                                  CountDownLatch start) throws InterruptedException {
        ready.countDown();
        start.await();
        storage.linkProviderMessageId(gatewayMsgId, PROVIDER, providerMessageId);
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
                     FROM sendium_dlr.provider_correlation
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

    private void ageCorrelation(String providerMessageId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                      UPDATE sendium_dlr.provider_correlation
                      SET created_at = CURRENT_TIMESTAMP - INTERVAL '4 days'
                      WHERE provider_name = ? AND provider_message_id = ?
                      """)) {
            statement.setString(1, PROVIDER);
            statement.setString(2, providerMessageId);
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
