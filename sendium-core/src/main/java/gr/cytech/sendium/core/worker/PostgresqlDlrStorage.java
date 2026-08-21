package gr.cytech.sendium.core.worker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class PostgresqlDlrStorage implements DlrStorage {
    private static final Logger logger = LoggerFactory.getLogger(PostgresqlDlrStorage.class);

    private static final int DEFAULT_LINK_MAX_ATTEMPTS = 20;
    private static final long DEFAULT_LINK_RETRY_INTERVAL_MILLIS = 200;
    private static final long EXPIRY_CHECK_INTERVAL_MILLIS = TimeUnit.HOURS.toMillis(1);
    private static final int MAX_DELIVERY_BATCH_SIZE = 1_000;
    private static final int STARTING_ATTEMPT = -1;

    private static final String STATE_COLUMNS = """
            gateway_message_id, account_id, system_id, source_address, destination_address,
            provider_name, provider_message_id, forward_dlr_url, reassembled_parts, provider_status,
            dlr_state, error_code, delivery_channel, delivery_status, delivery_attempt_count,
            last_attempt_at, next_attempt_at, last_delivery_result, resolved_at, updated_at
            """;

    private static final String SAVE_INITIAL_STATE_SQL = """
            INSERT INTO sendium_dlr.dlr_message
                (gateway_message_id, account_id, system_id, source_address, destination_address,
                 provider_name, provider_message_id, forward_dlr_url, reassembled_parts, provider_status,
                 dlr_state, error_code, delivery_channel, delivery_status, delivery_attempt_count,
                 last_attempt_at, next_attempt_at, last_delivery_result, resolved_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (gateway_message_id) DO UPDATE SET
                account_id = EXCLUDED.account_id,
                system_id = EXCLUDED.system_id,
                source_address = EXCLUDED.source_address,
                destination_address = EXCLUDED.destination_address,
                provider_name = EXCLUDED.provider_name,
                provider_message_id = EXCLUDED.provider_message_id,
                forward_dlr_url = EXCLUDED.forward_dlr_url,
                reassembled_parts = EXCLUDED.reassembled_parts,
                provider_status = EXCLUDED.provider_status,
                dlr_state = EXCLUDED.dlr_state,
                error_code = EXCLUDED.error_code,
                delivery_channel = EXCLUDED.delivery_channel,
                delivery_status = EXCLUDED.delivery_status,
                delivery_attempt_count = EXCLUDED.delivery_attempt_count,
                last_attempt_at = EXCLUDED.last_attempt_at,
                next_attempt_at = EXCLUDED.next_attempt_at,
                last_delivery_result = EXCLUDED.last_delivery_result,
                resolved_at = EXCLUDED.resolved_at,
                created_at = CURRENT_TIMESTAMP,
                updated_at = EXCLUDED.updated_at
            WHERE dlr_message.delivery_status = 'WAITING_PROVIDER'
            """;

    private static final String LINK_MESSAGE_SQL = """
            UPDATE sendium_dlr.dlr_message
            SET provider_name = ?, provider_message_id = ?, provider_status = 'SENT',
                updated_at = CURRENT_TIMESTAMP
            WHERE gateway_message_id = ? AND delivery_status = 'WAITING_PROVIDER'
            """;

    private static final String LOCK_MESSAGE_SQL = """
            SELECT 1
            FROM sendium_dlr.dlr_message
            WHERE gateway_message_id = ?
            FOR UPDATE
            """;

    private static final String LOCK_CORRELATION_SQL = """
            SELECT pg_advisory_xact_lock(hashtextextended(?, 0) # hashtextextended(?, 1))
            """;

    private static final String GET_CORRELATION_OWNER_SQL = """
            SELECT gateway_message_id
            FROM sendium_dlr.provider_correlation
            WHERE provider_name = ? AND provider_message_id = ?
            """;

    private static final String SAVE_CORRELATION_SQL = """
            WITH saved_correlation AS (
                INSERT INTO sendium_dlr.provider_correlation
                    (provider_name, provider_message_id, gateway_message_id)
                VALUES (?, ?, ?)
                ON CONFLICT (provider_name, provider_message_id) DO UPDATE SET
                    gateway_message_id = EXCLUDED.gateway_message_id,
                    created_at = CURRENT_TIMESTAMP
                RETURNING gateway_message_id
            )
            UPDATE sendium_dlr.dlr_message
            SET provider_name = NULL, provider_message_id = NULL, updated_at = CURRENT_TIMESTAMP
            WHERE provider_name = ? AND provider_message_id = ?
              AND gateway_message_id <> ?
              AND delivery_status = 'WAITING_PROVIDER'
              AND EXISTS (SELECT 1 FROM saved_correlation)
            """;

    private static final String DELETE_CORRELATIONS_SQL = """
            DELETE FROM sendium_dlr.provider_correlation
            WHERE gateway_message_id = ?
            """;

    private static final String GET_STATE_SQL = """
            SELECT %s
            FROM sendium_dlr.dlr_message
            WHERE gateway_message_id = ?
            """.formatted(STATE_COLUMNS);

    private static final String RESOLVE_STATE_SQL = """
            UPDATE sendium_dlr.dlr_message
            SET provider_name = ?,
                provider_message_id = ?,
                provider_status = ?,
                dlr_state = ?,
                error_code = ?,
                delivery_status = CASE WHEN delivery_channel = 'NONE' THEN delivery_status ELSE 'PENDING' END,
                next_attempt_at = CASE WHEN delivery_channel = 'HTTP' THEN CURRENT_TIMESTAMP ELSE NULL END,
                resolved_at = CURRENT_TIMESTAMP,
                updated_at = CURRENT_TIMESTAMP
            WHERE gateway_message_id = ? AND delivery_status = 'WAITING_PROVIDER'
            RETURNING %s
            """.formatted(STATE_COLUMNS);

    private static final String DELETE_STATE_SQL = """
            DELETE FROM sendium_dlr.dlr_message
            WHERE gateway_message_id = ?
            """;

    private static final String LIST_PENDING_SMPP_SQL = """
            SELECT %s
            FROM sendium_dlr.dlr_message
            WHERE system_id = ? AND delivery_channel = 'SMPP' AND delivery_status = 'PENDING'
            ORDER BY resolved_at, created_at, gateway_message_id
            """.formatted(STATE_COLUMNS);

    private static final String LIST_DUE_HTTP_SQL = """
            SELECT %s
            FROM sendium_dlr.dlr_message
            WHERE delivery_channel = 'HTTP' AND delivery_status = 'PENDING'
              AND next_attempt_at <= CURRENT_TIMESTAMP
            ORDER BY next_attempt_at, gateway_message_id
            LIMIT ?
            """.formatted(STATE_COLUMNS);

    private static final String START_DELIVERY_SQL = """
            UPDATE sendium_dlr.dlr_message
            SET delivery_attempt_count = delivery_attempt_count + 1,
                last_attempt_at = CURRENT_TIMESTAMP,
                updated_at = CURRENT_TIMESTAMP
            WHERE gateway_message_id = ? AND delivery_channel = ? AND delivery_status = 'PENDING'
            RETURNING %s
            """.formatted(STATE_COLUMNS);

    private static final String COMPLETE_DELIVERY_SQL = """
            DELETE FROM sendium_dlr.dlr_message
            WHERE gateway_message_id = ? AND delivery_status = 'PENDING' AND delivery_attempt_count = ?
            """;

    private static final String RETRY_DELIVERY_SQL = """
            UPDATE sendium_dlr.dlr_message
            SET last_delivery_result = ?, next_attempt_at = ?, updated_at = CURRENT_TIMESTAMP
            WHERE gateway_message_id = ? AND delivery_status = 'PENDING' AND delivery_attempt_count = ?
            """;

    private static final String FAIL_DELIVERY_SQL = """
            UPDATE sendium_dlr.dlr_message
            SET delivery_status = 'FAILED', last_delivery_result = ?, next_attempt_at = NULL,
                updated_at = CURRENT_TIMESTAMP
            WHERE gateway_message_id = ? AND delivery_status = 'PENDING' AND delivery_attempt_count = ?
            """;

    private static final String FAIL_INVALID_DELIVERY_SQL = """
            UPDATE sendium_dlr.dlr_message
            SET delivery_status = 'FAILED', last_delivery_result = ?, next_attempt_at = NULL,
                resolved_at = COALESCE(resolved_at, CURRENT_TIMESTAMP), updated_at = CURRENT_TIMESTAMP
            WHERE gateway_message_id = ? AND delivery_status = 'PENDING'
            """;

    private static final String DELETE_EXPIRED_CORRELATIONS_SQL = """
            DELETE FROM sendium_dlr.provider_correlation
            WHERE created_at < CURRENT_TIMESTAMP - INTERVAL '3 days'
            """;

    private static final String DELETE_EXPIRED_MESSAGES_SQL = """
            WITH expired_messages AS (
                SELECT gateway_message_id
                FROM sendium_dlr.dlr_message
                WHERE (delivery_status = 'WAITING_PROVIDER'
                           AND created_at < CURRENT_TIMESTAMP - INTERVAL '7 days')
                   OR (delivery_status IN ('PENDING', 'FAILED')
                           AND resolved_at < CURRENT_TIMESTAMP - INTERVAL '7 days')
                ORDER BY gateway_message_id
                FOR UPDATE
            )
            DELETE FROM sendium_dlr.dlr_message message
            USING expired_messages expired
            WHERE message.gateway_message_id = expired.gateway_message_id
            """;

    private final DataSource dataSource;
    private final int linkMaxAttempts;
    private final long linkRetryIntervalMillis;
    private final long expiryCheckIntervalMillis;
    private final ConcurrentHashMap<UUID, Integer> activeDeliveryAttempts = new ConcurrentHashMap<>();
    private final AtomicBoolean expiryInProgress = new AtomicBoolean();
    private volatile long lastExpiryCheck;

    public PostgresqlDlrStorage(DataSource dataSource) {
        this(dataSource, DEFAULT_LINK_MAX_ATTEMPTS, DEFAULT_LINK_RETRY_INTERVAL_MILLIS,
                EXPIRY_CHECK_INTERVAL_MILLIS);
    }

    PostgresqlDlrStorage(DataSource dataSource, int linkMaxAttempts,
                         long linkRetryIntervalMillis) {
        this(dataSource, linkMaxAttempts, linkRetryIntervalMillis, EXPIRY_CHECK_INTERVAL_MILLIS);
    }

    PostgresqlDlrStorage(DataSource dataSource, int linkMaxAttempts,
                         long linkRetryIntervalMillis, long expiryCheckIntervalMillis) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        if (linkMaxAttempts < 1 || linkRetryIntervalMillis < 0 || expiryCheckIntervalMillis < 0) {
            throw new IllegalArgumentException("Invalid storage retry or expiry policy");
        }
        this.linkMaxAttempts = linkMaxAttempts;
        this.linkRetryIntervalMillis = linkRetryIntervalMillis;
        this.expiryCheckIntervalMillis = expiryCheckIntervalMillis;
    }

    @Override
    public void saveInitialState(MessageState state) {
        saveInitialStates(List.of(state));
    }

    @Override
    public void saveInitialStates(List<MessageState> states) {
        Objects.requireNonNull(states, "states");
        if (states.isEmpty()) {
            return;
        }

        Map<UUID, MessageState> finalStatesByGateway = new LinkedHashMap<>();
        for (MessageState state : states) {
            Objects.requireNonNull(state, "state");
            validateState(state);
            UUID gatewayMsgId = parseGatewayId(state.getGatewayMsgId());
            finalStatesByGateway.remove(gatewayMsgId);
            finalStatesByGateway.put(gatewayMsgId, state);
        }
        checkExpiry();

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                List<Map.Entry<UUID, MessageState>> entries = new ArrayList<>(finalStatesByGateway.entrySet());
                List<MessageState> correlatedStates = entries.stream()
                        .map(Map.Entry::getValue)
                        .filter(state -> state.getProviderMessageId() != null)
                        .sorted(Comparator.comparing(MessageState::getProviderName)
                                .thenComparing(MessageState::getProviderMessageId))
                        .toList();
                lockCorrelations(connection, correlatedStates);
                lockInitialMessageOwners(connection, entries, correlatedStates);

                List<Map.Entry<UUID, MessageState>> saved = new ArrayList<>();
                try (PreparedStatement statement = connection.prepareStatement(SAVE_INITIAL_STATE_SQL)) {
                    for (Map.Entry<UUID, MessageState> entry : entries) {
                        setStateParameters(connection, statement, entry.getKey(), entry.getValue());
                        if (statement.executeUpdate() == 1) {
                            saved.add(entry);
                        }
                    }
                }
                for (Map.Entry<UUID, MessageState> entry : saved) {
                    deleteCorrelations(connection, entry.getKey());
                }
                for (int index = 0; index < saved.size(); index++) {
                    MessageState state = saved.get(index).getValue();
                    if (state.getProviderMessageId() != null && isLastCorrelationOwner(saved, index, state)) {
                        saveCorrelation(connection, state.getProviderName(), state.getProviderMessageId(),
                                saved.get(index).getKey());
                    }
                }
                connection.commit();
            } catch (SQLException e) {
                rollback(connection, e);
                throw e;
            }
        } catch (SQLException e) {
            throw failure("save initial DLR states", e);
        }
    }

    @Override
    public void linkProviderMessageId(String gatewayMessageId, String providerName, String providerMessageId) {
        checkExpiry();
        requireCorrelation(providerName, providerMessageId);
        UUID gatewayId = parseGatewayId(gatewayMessageId);

        for (int attempt = 0; attempt < linkMaxAttempts; attempt++) {
            if (tryLinkProviderMessageId(gatewayId, providerName, providerMessageId)) {
                return;
            }
            if (attempt + 1 < linkMaxAttempts) {
                sleepBeforeLinkRetry();
            }
        }
        throw new DlrStorageException("Gateway message state not found while linking provider message ID");
    }

    @Override
    public Optional<MessageState> resolveDlr(String providerName, String providerMessageId,
                                             MessageState.MessageStatus status, int dlrState, String errorCode) {
        Objects.requireNonNull(status, "status");
        requireCorrelation(providerName, providerMessageId);
        checkExpiry();

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                lockCorrelation(connection, providerName, providerMessageId);
                Optional<UUID> gatewayMessageId = findCorrelationOwner(
                        connection, providerName, providerMessageId);
                if (gatewayMessageId.isEmpty() || !lockMessage(connection, gatewayMessageId.get())) {
                    connection.rollback();
                    return Optional.empty();
                }
                Optional<MessageState> state = resolveState(connection, gatewayMessageId.get(), providerName,
                        providerMessageId, status, dlrState, errorCode);
                if (state.isEmpty()) {
                    connection.rollback();
                    return Optional.empty();
                }
                deleteCorrelations(connection, gatewayMessageId.get());
                if (state.get().getDeliveryChannel() == MessageState.DeliveryChannel.NONE) {
                    deleteState(connection, gatewayMessageId.get());
                }
                connection.commit();
                return state;
            } catch (SQLException e) {
                rollback(connection, e);
                throw e;
            }
        } catch (SQLException e) {
            throw failure("resolve DLR state", e);
        }
    }

    @Override
    public Optional<MessageState> getState(String gatewayMsgId) {
        checkExpiry();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(GET_STATE_SQL)) {
            statement.setObject(1, parseGatewayId(gatewayMsgId));
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(readState(resultSet)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw failure("read DLR state", e);
        }
    }

    @Override
    public List<MessageState> listPendingSmppDeliveries(String systemId) {
        checkExpiry();
        if (systemId == null || systemId.isBlank()) {
            return List.of();
        }
        return listStates(LIST_PENDING_SMPP_SQL, statement -> statement.setString(1, systemId),
                "list pending SMPP deliveries");
    }

    @Override
    public List<MessageState> listDueHttpDeliveries(int limit) {
        checkExpiry();
        if (limit < 1) {
            throw new IllegalArgumentException("Delivery limit must be positive");
        }
        int boundedLimit = Math.min(limit, MAX_DELIVERY_BATCH_SIZE);
        return listStates(LIST_DUE_HTTP_SQL, statement -> statement.setInt(1, boundedLimit),
                "list due HTTP deliveries");
    }

    @Override
    public Optional<MessageState> startDeliveryAttempt(String gatewayMsgId,
                                                       MessageState.DeliveryChannel expectedChannel) {
        Objects.requireNonNull(expectedChannel, "expectedChannel");
        if (expectedChannel == MessageState.DeliveryChannel.NONE) {
            throw new IllegalArgumentException("A delivery attempt requires HTTP or SMPP channel");
        }
        UUID gatewayId = parseGatewayId(gatewayMsgId);
        if (activeDeliveryAttempts.putIfAbsent(gatewayId, STARTING_ATTEMPT) != null) {
            return Optional.empty();
        }

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(START_DELIVERY_SQL)) {
            statement.setObject(1, gatewayId);
            statement.setString(2, expectedChannel.name());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    activeDeliveryAttempts.remove(gatewayId, STARTING_ATTEMPT);
                    return Optional.empty();
                }
                MessageState state = readState(resultSet);
                activeDeliveryAttempts.replace(gatewayId, STARTING_ATTEMPT, state.getDeliveryAttemptCount());
                return Optional.of(state);
            }
        } catch (SQLException e) {
            activeDeliveryAttempts.remove(gatewayId, STARTING_ATTEMPT);
            throw failure("start DLR delivery attempt", e);
        }
    }

    @Override
    public boolean completeDelivery(String gatewayMsgId, int expectedAttempt) {
        return finishAttempt(gatewayMsgId, expectedAttempt, COMPLETE_DELIVERY_SQL,
                statement -> {
                    statement.setObject(1, parseGatewayId(gatewayMsgId));
                    statement.setInt(2, expectedAttempt);
                }, "complete DLR delivery");
    }

    @Override
    public boolean retryDelivery(String gatewayMsgId, int expectedAttempt, String result, long nextAttemptAt) {
        return finishAttempt(gatewayMsgId, expectedAttempt, RETRY_DELIVERY_SQL,
                statement -> {
                    statement.setString(1, normalizeResult(result));
                    statement.setObject(2, toOffsetDateTime(nextAttemptAt));
                    statement.setObject(3, parseGatewayId(gatewayMsgId));
                    statement.setInt(4, expectedAttempt);
                }, "retry DLR delivery");
    }

    @Override
    public boolean failDelivery(String gatewayMsgId, int expectedAttempt, String result) {
        return finishAttempt(gatewayMsgId, expectedAttempt, FAIL_DELIVERY_SQL,
                statement -> {
                    statement.setString(1, normalizeResult(result));
                    statement.setObject(2, parseGatewayId(gatewayMsgId));
                    statement.setInt(3, expectedAttempt);
                }, "fail DLR delivery");
    }

    @Override
    public boolean failInvalidDelivery(String gatewayMsgId, String result) {
        checkExpiry();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(FAIL_INVALID_DELIVERY_SQL)) {
            statement.setString(1, normalizeResult(result));
            statement.setObject(2, parseGatewayId(gatewayMsgId));
            return statement.executeUpdate() == 1;
        } catch (SQLException e) {
            throw failure("mark invalid DLR delivery failed", e);
        }
    }

    private boolean finishAttempt(String gatewayMsgId, int expectedAttempt, String sql,
                                  StatementBinder binder, String operation) {
        if (expectedAttempt < 1) {
            throw new IllegalArgumentException("Expected attempt must be positive");
        }
        checkExpiry();
        UUID gatewayId = parseGatewayId(gatewayMsgId);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            return statement.executeUpdate() == 1;
        } catch (SQLException e) {
            throw failure(operation, e);
        } finally {
            activeDeliveryAttempts.remove(gatewayId, expectedAttempt);
        }
    }

    private List<MessageState> listStates(String sql, StatementBinder binder, String operation) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<MessageState> states = new ArrayList<>();
                while (resultSet.next()) {
                    states.add(readState(resultSet));
                }
                return states;
            }
        } catch (SQLException e) {
            throw failure(operation, e);
        }
    }

    private void lockCorrelations(Connection connection, List<MessageState> correlatedStates) throws SQLException {
        String previousProviderName = null;
        String previousProviderMessageId = null;
        for (MessageState state : correlatedStates) {
            if (!state.getProviderName().equals(previousProviderName) ||
                    !state.getProviderMessageId().equals(previousProviderMessageId)) {
                lockCorrelation(connection, state.getProviderName(), state.getProviderMessageId());
                previousProviderName = state.getProviderName();
                previousProviderMessageId = state.getProviderMessageId();
            }
        }
    }

    private void lockInitialMessageOwners(Connection connection, List<Map.Entry<UUID, MessageState>> entries,
                                          List<MessageState> correlatedStates) throws SQLException {
        List<UUID> messageIdsToLock = entries.stream().map(Map.Entry::getKey).collect(ArrayList::new,
                ArrayList::add, ArrayList::addAll);
        for (MessageState state : correlatedStates) {
            findCorrelationOwner(connection, state.getProviderName(), state.getProviderMessageId())
                    .ifPresent(messageIdsToLock::add);
        }
        for (UUID messageId : messageIdsToLock.stream().distinct()
                .sorted(Comparator.comparing(UUID::toString)).toList()) {
            lockMessage(connection, messageId);
        }
    }

    private boolean tryLinkProviderMessageId(UUID gatewayMessageId, String providerName, String providerMessageId) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                lockCorrelation(connection, providerName, providerMessageId);
                Optional<UUID> previousOwner = findCorrelationOwner(connection, providerName, providerMessageId);
                List<UUID> messageIdsToLock = new ArrayList<>();
                messageIdsToLock.add(gatewayMessageId);
                previousOwner.ifPresent(messageIdsToLock::add);
                boolean targetFound = false;
                for (UUID messageId : messageIdsToLock.stream().distinct()
                        .sorted(Comparator.comparing(UUID::toString)).toList()) {
                    boolean found = lockMessage(connection, messageId);
                    if (messageId.equals(gatewayMessageId)) {
                        targetFound = found;
                    }
                }
                if (!targetFound) {
                    connection.rollback();
                    return false;
                }
                saveCorrelation(connection, providerName, providerMessageId, gatewayMessageId);
                if (!markAsSent(connection, gatewayMessageId, providerName, providerMessageId)) {
                    connection.rollback();
                    return false;
                }
                connection.commit();
                return true;
            } catch (SQLException e) {
                rollback(connection, e);
                throw e;
            }
        } catch (SQLException e) {
            throw failure("link provider DLR ID", e);
        }
    }

    private void lockCorrelation(Connection connection, String providerName,
                                 String providerMessageId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(LOCK_CORRELATION_SQL)) {
            statement.setString(1, providerName);
            statement.setString(2, providerMessageId);
            statement.execute();
        }
    }

    private Optional<UUID> findCorrelationOwner(Connection connection, String providerName,
                                                String providerMessageId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(GET_CORRELATION_OWNER_SQL)) {
            statement.setString(1, providerName);
            statement.setString(2, providerMessageId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ?
                        Optional.of(resultSet.getObject("gateway_message_id", UUID.class))
                        : Optional.empty();
            }
        }
    }

    private boolean lockMessage(Connection connection, UUID gatewayMsgId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(LOCK_MESSAGE_SQL)) {
            statement.setObject(1, gatewayMsgId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private boolean markAsSent(Connection connection, UUID gatewayMessageId,
                               String providerName, String providerMessageId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(LINK_MESSAGE_SQL)) {
            statement.setString(1, providerName);
            statement.setString(2, providerMessageId);
            statement.setObject(3, gatewayMessageId);
            return statement.executeUpdate() == 1;
        }
    }

    private Optional<MessageState> resolveState(Connection connection, UUID gatewayMessageId,
                                                String providerName, String providerMessageId,
                                                MessageState.MessageStatus status, int dlrState,
                                                String errorCode) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(RESOLVE_STATE_SQL)) {
            statement.setString(1, providerName);
            statement.setString(2, providerMessageId);
            statement.setString(3, status.name());
            statement.setInt(4, dlrState);
            statement.setString(5, errorCode);
            statement.setObject(6, gatewayMessageId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(readState(resultSet)) : Optional.empty();
            }
        }
    }

    private void deleteCorrelations(Connection connection, UUID gatewayMessageId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(DELETE_CORRELATIONS_SQL)) {
            statement.setObject(1, gatewayMessageId);
            statement.executeUpdate();
        }
    }

    private void deleteState(Connection connection, UUID gatewayMsgId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(DELETE_STATE_SQL)) {
            statement.setObject(1, gatewayMsgId);
            statement.executeUpdate();
        }
    }

    private void saveCorrelation(Connection connection, String providerName,
                                 String providerMessageId, UUID gatewayMessageId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SAVE_CORRELATION_SQL)) {
            statement.setString(1, providerName);
            statement.setString(2, providerMessageId);
            statement.setObject(3, gatewayMessageId);
            statement.setString(4, providerName);
            statement.setString(5, providerMessageId);
            statement.setObject(6, gatewayMessageId);
            statement.executeUpdate();
        }
    }

    private void setStateParameters(Connection connection, PreparedStatement statement, UUID gatewayMsgId,
                                    MessageState state) throws SQLException {
        statement.setObject(1, gatewayMsgId);
        statement.setString(2, state.getAccountId());
        statement.setString(3, state.getSystemId());
        statement.setString(4, state.getSourceAddr());
        statement.setString(5, state.getDestAddr());
        statement.setString(6, state.getProviderName());
        statement.setString(7, state.getProviderMessageId());
        statement.setString(8, state.getForwardDlrUrl());
        setStringArray(connection, statement, 9, state.getReassembledParts());
        statement.setString(10, state.getStatus().name());
        if (state.getDlrState() == null) {
            statement.setNull(11, Types.INTEGER);
        } else {
            statement.setInt(11, state.getDlrState());
        }
        statement.setString(12, state.getErrorCode());
        statement.setString(13, state.getDeliveryChannel().name());
        statement.setString(14, state.getDeliveryStatus().name());
        statement.setInt(15, state.getDeliveryAttemptCount());
        setTimestamp(statement, 16, state.getLastAttemptAt());
        setTimestamp(statement, 17, state.getNextAttemptAt());
        statement.setString(18, state.getLastDeliveryResult());
        setTimestamp(statement, 19, state.getResolvedAt());
        statement.setObject(20, toOffsetDateTime(state.getTimestamp()));
    }

    private MessageState readState(ResultSet resultSet) throws SQLException {
        MessageState state = new MessageState(
                resultSet.getObject("gateway_message_id", UUID.class).toString(),
                resultSet.getString("account_id"),
                resultSet.getString("system_id"),
                resultSet.getString("source_address"),
                resultSet.getString("destination_address"),
                resultSet.getString("forward_dlr_url"));
        state.setProviderName(resultSet.getString("provider_name"));
        state.setProviderMessageId(resultSet.getString("provider_message_id"));
        state.setReassembledParts(readStringArray(resultSet, "reassembled_parts"));
        state.setStatus(MessageState.MessageStatus.valueOf(resultSet.getString("provider_status")));
        state.setDlrState(resultSet.getObject("dlr_state", Integer.class));
        state.setErrorCode(resultSet.getString("error_code"));
        state.setDeliveryChannel(MessageState.DeliveryChannel.valueOf(resultSet.getString("delivery_channel")));
        state.setDeliveryStatus(MessageState.DeliveryStatus.valueOf(resultSet.getString("delivery_status")));
        state.setDeliveryAttemptCount(resultSet.getInt("delivery_attempt_count"));
        state.setLastAttemptAt(readEpochMillis(resultSet, "last_attempt_at"));
        state.setNextAttemptAt(readEpochMillis(resultSet, "next_attempt_at"));
        state.setLastDeliveryResult(resultSet.getString("last_delivery_result"));
        state.setResolvedAt(readEpochMillis(resultSet, "resolved_at"));
        state.setTimestamp(readRequiredEpochMillis(resultSet, "updated_at"));
        return state;
    }

    private void validateState(MessageState state) {
        if (state.getStatus() == null || state.getDeliveryChannel() == null || state.getDeliveryStatus() == null) {
            throw new IllegalArgumentException("DLR state statuses and delivery channel are required");
        }
        if (state.getDeliveryAttemptCount() < 0) {
            throw new IllegalArgumentException("Delivery attempt count must not be negative");
        }
        if (state.getProviderName() == null && state.getProviderMessageId() == null) {
            validateDeliveryTarget(state);
            return;
        }
        if (state.getProviderName() == null || state.getProviderName().isBlank() ||
                state.getProviderMessageId() == null || state.getProviderMessageId().isBlank()) {
            throw new IllegalArgumentException(
                    "Provider name and provider message ID must either both be set or both be absent");
        }
        validateDeliveryTarget(state);
    }

    private void validateDeliveryTarget(MessageState state) {
        if (state.getDeliveryChannel() == MessageState.DeliveryChannel.HTTP &&
                (state.getForwardDlrUrl() == null || state.getForwardDlrUrl().isBlank())) {
            throw new IllegalArgumentException("HTTP delivery requires a nonblank callback URL");
        }
        if (state.getDeliveryChannel() == MessageState.DeliveryChannel.SMPP &&
                (state.getSystemId() == null || state.getSystemId().isBlank())) {
            throw new IllegalArgumentException("SMPP delivery requires a nonblank system ID");
        }
    }

    private boolean isLastCorrelationOwner(List<Map.Entry<UUID, MessageState>> states, int index,
                                           MessageState candidate) {
        for (int laterIndex = index + 1; laterIndex < states.size(); laterIndex++) {
            MessageState later = states.get(laterIndex).getValue();
            if (candidate.getProviderName().equals(later.getProviderName()) &&
                    candidate.getProviderMessageId().equals(later.getProviderMessageId())) {
                return false;
            }
        }
        return true;
    }

    private void requireCorrelation(String providerName, String providerMessageId) {
        if (providerName == null || providerName.isBlank() ||
                providerMessageId == null || providerMessageId.isBlank()) {
            throw new IllegalArgumentException("Provider name and provider message ID must not be blank");
        }
    }

    private String normalizeResult(String result) {
        return result == null || result.isBlank() ? null : result.trim();
    }

    private void setTimestamp(PreparedStatement statement, int index, Long epochMillis) throws SQLException {
        if (epochMillis == null) {
            statement.setNull(index, Types.TIMESTAMP_WITH_TIMEZONE);
        } else {
            statement.setObject(index, toOffsetDateTime(epochMillis));
        }
    }

    private OffsetDateTime toOffsetDateTime(long epochMillis) {
        return OffsetDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneOffset.UTC);
    }

    private Long readEpochMillis(ResultSet resultSet, String columnName) throws SQLException {
        OffsetDateTime value = resultSet.getObject(columnName, OffsetDateTime.class);
        return value == null ? null : value.toInstant().toEpochMilli();
    }

    private long readRequiredEpochMillis(ResultSet resultSet, String columnName) throws SQLException {
        return resultSet.getObject(columnName, OffsetDateTime.class).toInstant().toEpochMilli();
    }

    private List<String> readStringArray(ResultSet resultSet, String columnName) throws SQLException {
        Array array = resultSet.getArray(columnName);
        if (array == null) {
            return null;
        }
        return new ArrayList<>(Arrays.asList((String[]) array.getArray()));
    }

    private void setStringArray(Connection connection, PreparedStatement statement, int index,
                                List<String> values) throws SQLException {
        if (values == null) {
            statement.setNull(index, Types.ARRAY);
            return;
        }
        statement.setArray(index, connection.createArrayOf("text", values.toArray(String[]::new)));
    }

    private void sleepBeforeLinkRetry() {
        try {
            Thread.sleep(linkRetryIntervalMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DlrStorageException("Interrupted while linking provider message ID", e);
        }
    }

    private void checkExpiry() {
        if (System.currentTimeMillis() - lastExpiryCheck < expiryCheckIntervalMillis ||
                !expiryInProgress.compareAndSet(false, true)) {
            return;
        }
        try {
            if (System.currentTimeMillis() - lastExpiryCheck >= expiryCheckIntervalMillis) {
                deleteExpiredState();
            }
        } catch (RuntimeException e) {
            logger.warn("DLR retention cleanup failed; retrying after the next interval");
        } finally {
            lastExpiryCheck = System.currentTimeMillis();
            expiryInProgress.set(false);
        }
    }

    private void deleteExpiredState() {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement messages = connection.prepareStatement(DELETE_EXPIRED_MESSAGES_SQL);
                 PreparedStatement correlations = connection.prepareStatement(DELETE_EXPIRED_CORRELATIONS_SQL)) {
                messages.executeUpdate();
                correlations.executeUpdate();
                connection.commit();
            } catch (SQLException e) {
                rollback(connection, e);
                throw e;
            }
        } catch (SQLException e) {
            throw failure("expire DLR state", e);
        }
    }

    private UUID parseGatewayId(String gatewayMsgId) {
        try {
            return UUID.fromString(gatewayMsgId);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new DlrStorageException("Invalid gateway message ID", e);
        }
    }

    private void rollback(Connection connection, SQLException failure) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }

    private DlrStorageException failure(String operation, SQLException cause) {
        logger.error("Failed to {}: sqlState={} errorCode={} reason={}",
                operation, cause.getSQLState(), cause.getErrorCode(), cause.getMessage());
        logger.debug("DLR storage failure details while attempting to {}", operation, cause);
        return new DlrStorageException("Failed to " + operation, cause);
    }

    @FunctionalInterface
    private interface StatementBinder {
        void bind(PreparedStatement statement) throws SQLException;
    }
}
