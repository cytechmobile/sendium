package gr.cytech.sendium.core.worker;

import gr.cytech.sendium.core.message.StandardMessage;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
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

    private static final String SAVE_INITIAL_STATE_SQL = """
            INSERT INTO sendium_dlr.tracked_message
                (gateway_message_id, account_id, system_id, source_address, destination_address,
                 provider_name, provider_message_id, forward_dlr_url, reassembled_parts, status, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (gateway_message_id) DO UPDATE SET
                account_id = EXCLUDED.account_id,
                system_id = EXCLUDED.system_id,
                source_address = EXCLUDED.source_address,
                destination_address = EXCLUDED.destination_address,
                provider_name = EXCLUDED.provider_name,
                provider_message_id = EXCLUDED.provider_message_id,
                forward_dlr_url = EXCLUDED.forward_dlr_url,
                reassembled_parts = EXCLUDED.reassembled_parts,
                status = EXCLUDED.status,
                created_at = CURRENT_TIMESTAMP,
                updated_at = EXCLUDED.updated_at
            """;

    private static final String LINK_MESSAGE_SQL = """
            UPDATE sendium_dlr.tracked_message
            SET provider_name = ?, provider_message_id = ?, status = 'SENT', updated_at = CURRENT_TIMESTAMP
            WHERE gateway_message_id = ?
            """;

    private static final String LOCK_MESSAGE_SQL = """
            SELECT 1
            FROM sendium_dlr.tracked_message
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
            UPDATE sendium_dlr.tracked_message
            SET provider_name = NULL, provider_message_id = NULL, updated_at = CURRENT_TIMESTAMP
            WHERE provider_name = ? AND provider_message_id = ?
              AND gateway_message_id <> ?
              AND EXISTS (SELECT 1 FROM saved_correlation)
            """;

    private static final String DELETE_CORRELATIONS_SQL = """
            DELETE FROM sendium_dlr.provider_correlation
            WHERE gateway_message_id = ?
            """;

    private static final String GET_STATE_SQL = """
            SELECT tm.gateway_message_id, tm.account_id, tm.system_id, tm.source_address,
                   tm.destination_address, tm.provider_name, tm.provider_message_id, tm.forward_dlr_url,
                   tm.reassembled_parts, tm.status, tm.updated_at
            FROM sendium_dlr.tracked_message tm
            WHERE tm.gateway_message_id = ?
            """;

    private static final String RESOLVE_STATE_SQL = """
            SELECT tm.gateway_message_id, tm.account_id, tm.system_id, tm.source_address,
                   tm.destination_address, tm.forward_dlr_url, tm.reassembled_parts,
                   correlation.provider_name, correlation.provider_message_id,
                   CURRENT_TIMESTAMP AS resolved_at
            FROM sendium_dlr.provider_correlation correlation
            JOIN sendium_dlr.tracked_message tm
                ON tm.gateway_message_id = correlation.gateway_message_id
            WHERE correlation.provider_name = ? AND correlation.provider_message_id = ?
              AND correlation.gateway_message_id = ?
            FOR UPDATE OF correlation
            """;

    private static final String DELETE_STATE_SQL = """
            DELETE FROM sendium_dlr.tracked_message
            WHERE gateway_message_id = ?
            """;

    private static final String MARK_FAILED_SQL = """
            UPDATE sendium_dlr.tracked_message
            SET status = 'FAILED', updated_at = CURRENT_TIMESTAMP
            WHERE gateway_message_id = ?
            """;

    private static final String DELETE_EXPIRED_CORRELATIONS_SQL = """
            DELETE FROM sendium_dlr.provider_correlation
            WHERE created_at < CURRENT_TIMESTAMP - INTERVAL '3 days'
            """;

    private static final String DELETE_EXPIRED_MESSAGES_SQL = """
            WITH expired_messages AS (
                SELECT gateway_message_id
                FROM sendium_dlr.tracked_message
                WHERE created_at < CURRENT_TIMESTAMP - INTERVAL '7 days'
                ORDER BY gateway_message_id
                FOR UPDATE
            )
            DELETE FROM sendium_dlr.tracked_message message
            USING expired_messages expired
            WHERE message.gateway_message_id = expired.gateway_message_id
            """;

    private static final String SAVE_UNPUSHED_DLR_SQL = """
            INSERT INTO sendium_dlr.unpushed_dlr
                (dlr_key, system_id, account_id, source_address, destination_address, serial,
                 message_id, dlr_state, error_code, acked, priority, reassembled_parts, generation_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (dlr_key) DO UPDATE SET
                system_id = EXCLUDED.system_id,
                account_id = EXCLUDED.account_id,
                source_address = EXCLUDED.source_address,
                destination_address = EXCLUDED.destination_address,
                serial = EXCLUDED.serial,
                message_id = EXCLUDED.message_id,
                dlr_state = EXCLUDED.dlr_state,
                error_code = EXCLUDED.error_code,
                acked = EXCLUDED.acked,
                priority = EXCLUDED.priority,
                reassembled_parts = EXCLUDED.reassembled_parts,
                generation_id = EXCLUDED.generation_id,
                created_at = CURRENT_TIMESTAMP
            """;

    private static final String GET_UNPUSHED_DLRS_SQL = """
            SELECT dlr_key, system_id, account_id, source_address, destination_address, serial,
                   message_id, dlr_state, error_code, acked, priority, reassembled_parts, generation_id
            FROM sendium_dlr.unpushed_dlr
            WHERE system_id = ?
            ORDER BY created_at, dlr_key
            """;

    private static final String DELETE_UNPUSHED_DLR_SQL = """
            DELETE FROM sendium_dlr.unpushed_dlr
            WHERE dlr_key = ? AND generation_id = ?
            """;

    private static final String DELETE_EXPIRED_UNPUSHED_DLRS_SQL = """
            DELETE FROM sendium_dlr.unpushed_dlr
            WHERE created_at < CURRENT_TIMESTAMP - INTERVAL '7 days'
            RETURNING dlr_key, generation_id
            """;

    private final DataSource dataSource;
    private final int linkMaxAttempts;
    private final long linkRetryIntervalMillis;
    private final long expiryCheckIntervalMillis;
    private final Object unpushedDlrStateLock = new Object();
    private final ConcurrentHashMap<String, UUID> claimedUnpushedDlrKeys = new ConcurrentHashMap<>();
    private final IdentityHashMap<StandardMessage, UUID> claimedUnpushedDlrGenerations = new IdentityHashMap<>();
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

        List<MessageState> suppliedStates = states.stream()
                .map(state -> Objects.requireNonNull(state, "state"))
                .toList();
        suppliedStates.forEach(this::validateCorrelationFields);
        Map<UUID, MessageState> finalStatesByGateway = new LinkedHashMap<>();
        for (MessageState state : suppliedStates) {
            UUID gatewayMsgId = parseGatewayId(state.getGatewayMsgId());
            // Reinsert duplicate IDs so batch order reflects each gateway's final occurrence.
            finalStatesByGateway.remove(gatewayMsgId);
            finalStatesByGateway.put(gatewayMsgId, state);
        }
        List<UUID> gatewayMsgIds = List.copyOf(finalStatesByGateway.keySet());
        List<MessageState> checkedStates = List.copyOf(finalStatesByGateway.values());
        checkExpiry();

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement saveStates = connection.prepareStatement(SAVE_INITIAL_STATE_SQL);
                 PreparedStatement deleteCorrelations = connection.prepareStatement(DELETE_CORRELATIONS_SQL);
                 PreparedStatement saveCorrelations = connection.prepareStatement(SAVE_CORRELATION_SQL)) {
                List<MessageState> correlatedStates = checkedStates.stream()
                        .filter(state -> state.getProviderMessageId() != null)
                        .sorted(Comparator.comparing(MessageState::getProviderName)
                                .thenComparing(MessageState::getProviderMessageId))
                        .toList();
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
                List<UUID> messageIdsToLock = new ArrayList<>(gatewayMsgIds);
                for (MessageState state : correlatedStates) {
                    findCorrelationOwner(connection, state.getProviderName(), state.getProviderMessageId())
                            .ifPresent(messageIdsToLock::add);
                }
                for (UUID messageId : messageIdsToLock.stream()
                        .distinct()
                        .sorted(Comparator.comparing(UUID::toString))
                        .toList()) {
                    lockMessage(connection, messageId);
                }
                int correlationCount = 0;
                for (int index = 0; index < checkedStates.size(); index++) {
                    MessageState state = checkedStates.get(index);
                    UUID gatewayMsgId = gatewayMsgIds.get(index);
                    setStateParameters(connection, saveStates, gatewayMsgId, state);
                    saveStates.addBatch();
                    deleteCorrelations.setObject(1, gatewayMsgId);
                    deleteCorrelations.addBatch();
                    if (state.getProviderMessageId() != null &&
                            isLastCorrelationOwner(checkedStates, index, state)) {
                        setCorrelationParameters(saveCorrelations, state.getProviderName(),
                                state.getProviderMessageId(), gatewayMsgId);
                        saveCorrelations.addBatch();
                        correlationCount++;
                    }
                }

                saveStates.executeBatch();
                deleteCorrelations.executeBatch();
                if (correlationCount > 0) {
                    saveCorrelations.executeBatch();
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
    public Optional<MessageState> resolveAndRemoveDlr(String providerName, String providerMessageId,
                                                      MessageState.MessageStatus status) {
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
                Optional<MessageState> state = lockResolvedState(
                        connection, providerName, providerMessageId, gatewayMessageId.get(), status);
                if (state.isEmpty()) {
                    connection.rollback();
                    return Optional.empty();
                }
                deleteState(connection, parseGatewayId(state.get().getGatewayMsgId()));
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
                return resultSet.next() ? Optional.of(readTrackedState(resultSet)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw failure("read DLR state", e);
        }
    }

    @Override
    public boolean markAsFailed(String gatewayMsgId) {
        checkExpiry();

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(MARK_FAILED_SQL)) {
            statement.setObject(1, parseGatewayId(gatewayMsgId));
            return statement.executeUpdate() == 1;
        } catch (SQLException e) {
            throw failure("mark DLR state as failed", e);
        }
    }

    @Override
    public boolean saveUnpushedDlr(StandardMessage message) {
        checkExpiry();
        if (!isValidUnpushedDlr(message)) {
            return false;
        }

        UnpushedDlr dlr = UnpushedDlr.fromMessage(message);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(SAVE_UNPUSHED_DLR_SQL)) {
            statement.setString(1, getUnpushedDlrKey(message));
            statement.setString(2, dlr.systemId);
            statement.setString(3, dlr.accountId);
            statement.setString(4, dlr.from);
            statement.setString(5, dlr.to);
            statement.setString(6, dlr.serial);
            statement.setInt(7, dlr.msgId);
            statement.setInt(8, dlr.state);
            statement.setString(9, dlr.errcode);
            statement.setBoolean(10, dlr.acked);
            statement.setInt(11, dlr.priority);
            setStringArray(connection, statement, 12, dlr.reassembledParts);
            statement.setObject(13, UUID.randomUUID());
            return statement.executeUpdate() == 1;
        } catch (SQLException e) {
            throw failure("save unpushed DLR", e);
        }
    }

    @Override
    public List<StandardMessage> getUnpushedDlrs(String systemId) {
        return loadUnpushedDlrs(systemId, false);
    }

    @Override
    public List<StandardMessage> claimUnpushedDlrs(String systemId) {
        return loadUnpushedDlrs(systemId, true);
    }

    @Override
    public boolean removeUnpushedDlr(StandardMessage message) {
        if (!isValidUnpushedDlr(message)) {
            return false;
        }

        String key = getUnpushedDlrKey(message);
        synchronized (unpushedDlrStateLock) {
            UUID generationId = claimedUnpushedDlrGenerations.get(message);
            if (generationId == null) {
                return false;
            }
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(DELETE_UNPUSHED_DLR_SQL)) {
                statement.setString(1, key);
                statement.setObject(2, generationId);
                boolean removed = statement.executeUpdate() == 1;
                claimedUnpushedDlrGenerations.remove(message);
                claimedUnpushedDlrKeys.remove(key, generationId);
                return removed;
            } catch (SQLException e) {
                throw failure("remove unpushed DLR", e);
            }
        }
    }

    @Override
    public void releaseUnpushedDlrClaim(StandardMessage message) {
        if (!isValidUnpushedDlr(message)) {
            return;
        }

        synchronized (unpushedDlrStateLock) {
            UUID generationId = claimedUnpushedDlrGenerations.remove(message);
            if (generationId != null) {
                claimedUnpushedDlrKeys.remove(getUnpushedDlrKey(message), generationId);
            }
        }
    }

    private List<StandardMessage> loadUnpushedDlrs(String systemId, boolean claimForReplay) {
        checkExpiry();
        if (systemId == null || systemId.isBlank()) {
            return List.of();
        }

        synchronized (unpushedDlrStateLock) {
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(GET_UNPUSHED_DLRS_SQL)) {
                statement.setString(1, systemId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    List<StandardMessage> messages = new ArrayList<>();
                    while (resultSet.next()) {
                        String key = resultSet.getString("dlr_key");
                        UUID generationId = resultSet.getObject("generation_id", UUID.class);
                        StandardMessage message = readUnpushedDlr(resultSet).toMessage();
                        if (!claimForReplay) {
                            messages.add(message);
                        } else if (claimedUnpushedDlrKeys.putIfAbsent(key, generationId) == null) {
                            claimedUnpushedDlrGenerations.put(message, generationId);
                            messages.add(message);
                        }
                    }
                    return messages;
                }
            } catch (SQLException e) {
                throw failure("read unpushed DLRs", e);
            }
        }
    }

    private UnpushedDlr readUnpushedDlr(ResultSet resultSet) throws SQLException {
        UnpushedDlr dlr = new UnpushedDlr();
        dlr.systemId = resultSet.getString("system_id");
        dlr.accountId = resultSet.getString("account_id");
        dlr.from = resultSet.getString("source_address");
        dlr.to = resultSet.getString("destination_address");
        dlr.serial = resultSet.getString("serial");
        dlr.msgId = resultSet.getInt("message_id");
        dlr.state = resultSet.getInt("dlr_state");
        dlr.errcode = resultSet.getString("error_code");
        dlr.acked = resultSet.getBoolean("acked");
        dlr.priority = resultSet.getInt("priority");
        dlr.reassembledParts = readStringArray(resultSet, "reassembled_parts");
        return dlr;
    }

    private boolean isValidUnpushedDlr(StandardMessage message) {
        return message != null && message.type == StandardMessage.MSG_DLR &&
                message.systemId != null && !message.systemId.isBlank();
    }

    private String getUnpushedDlrKey(StandardMessage message) {
        return String.join("|",
                nullToEmpty(message.systemId),
                nullToEmpty(message.serial),
                String.valueOf(message.state),
                nullToEmpty(message.errcode),
                String.valueOf(message.msgId));
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
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
                for (UUID messageId : messageIdsToLock.stream()
                        .distinct()
                        .sorted(Comparator.comparing(UUID::toString))
                        .toList()) {
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
                    throw new SQLException("Gateway message state disappeared while linking provider message ID");
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
        statement.setObject(11, OffsetDateTime.ofInstant(
                Instant.ofEpochMilli(state.getTimestamp()), ZoneOffset.UTC));
    }

    private void saveCorrelation(Connection connection, String providerName,
                                 String providerMessageId, UUID gatewayMessageId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SAVE_CORRELATION_SQL)) {
            setCorrelationParameters(statement, providerName, providerMessageId, gatewayMessageId);
            statement.executeUpdate();
        }
    }

    private void setCorrelationParameters(PreparedStatement statement, String providerName,
                                          String providerMessageId, UUID gatewayMessageId) throws SQLException {
        statement.setString(1, providerName);
        statement.setString(2, providerMessageId);
        statement.setObject(3, gatewayMessageId);
        statement.setString(4, providerName);
        statement.setString(5, providerMessageId);
        statement.setObject(6, gatewayMessageId);
    }

    private void validateCorrelationFields(MessageState state) {
        if (state.getProviderName() == null && state.getProviderMessageId() == null) {
            return;
        }
        if (state.getProviderName() == null || state.getProviderName().isBlank() ||
                state.getProviderMessageId() == null || state.getProviderMessageId().isBlank()) {
            throw new IllegalArgumentException(
                    "Provider name and provider message ID must either both be set or both be absent");
        }
    }

    private boolean isLastCorrelationOwner(List<MessageState> states, int index, MessageState candidate) {
        for (int laterIndex = index + 1; laterIndex < states.size(); laterIndex++) {
            MessageState later = states.get(laterIndex);
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

    private Optional<MessageState> lockResolvedState(Connection connection, String providerName,
                                                      String providerMessageId,
                                                      UUID gatewayMessageId,
                                                      MessageState.MessageStatus status) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(RESOLVE_STATE_SQL)) {
            statement.setString(1, providerName);
            statement.setString(2, providerMessageId);
            statement.setObject(3, gatewayMessageId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(readResolvedState(resultSet, status)) : Optional.empty();
            }
        }
    }

    private void deleteState(Connection connection, UUID gatewayMsgId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(DELETE_STATE_SQL)) {
            statement.setObject(1, gatewayMsgId);
            statement.executeUpdate();
        }
    }

    private MessageState readBaseState(ResultSet resultSet) throws SQLException {
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
        return state;
    }

    /**
     * Reads a {@link #GET_STATE_SQL} row, which carries the stored status and update time.
     */
    private MessageState readTrackedState(ResultSet resultSet) throws SQLException {
        MessageState state = readBaseState(resultSet);
        state.setStatus(MessageState.MessageStatus.valueOf(resultSet.getString("status")));
        state.setTimestamp(readEpochMillis(resultSet, "updated_at"));
        return state;
    }

    /**
     * Reads a {@link #RESOLVE_STATE_SQL} row, which carries the provider message ID and resolution time.
     */
    private MessageState readResolvedState(ResultSet resultSet,
                                           MessageState.MessageStatus status) throws SQLException {
        MessageState state = readBaseState(resultSet);
        state.setStatus(status);
        state.setTimestamp(readEpochMillis(resultSet, "resolved_at"));
        return state;
    }

    private long readEpochMillis(ResultSet resultSet, String columnName) throws SQLException {
        OffsetDateTime value = resultSet.getObject(columnName, OffsetDateTime.class);
        return value == null ? 0L : value.toInstant().toEpochMilli();
    }

    private List<String> readStringArray(ResultSet resultSet, String columnName) throws SQLException {
        Array array = resultSet.getArray(columnName);
        if (array == null) {
            return null;
        }
        //Arrays.asList, not List.of: a text[] column can legally hold NULL elements
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

    /**
     * Runs retention cleanup at most once per interval, on the thread that first observes the interval has elapsed.
     * Cleanup is best-effort maintenance: at most one thread runs it, every other caller proceeds immediately, and a
     * failed pass is logged and retried after the next interval instead of failing the operation that triggered it.
     */
    private void checkExpiry() {
        if (System.currentTimeMillis() - lastExpiryCheck < expiryCheckIntervalMillis) {
            return;
        }
        if (!expiryInProgress.compareAndSet(false, true)) {
            return;
        }
        try {
            if (System.currentTimeMillis() - lastExpiryCheck < expiryCheckIntervalMillis) {
                return;
            }
            deleteExpiredState();
        } catch (RuntimeException e) {
            logger.warn("DLR retention cleanup failed; retrying after the next interval");
        } finally {
            lastExpiryCheck = System.currentTimeMillis();
            expiryInProgress.set(false);
        }
    }

    private void deleteExpiredState() {
        Map<String, UUID> expiredUnpushedDlrKeys = new HashMap<>();
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement correlations = connection.prepareStatement(DELETE_EXPIRED_CORRELATIONS_SQL);
                 PreparedStatement messages = connection.prepareStatement(DELETE_EXPIRED_MESSAGES_SQL);
                 PreparedStatement unpushedDlrs = connection.prepareStatement(DELETE_EXPIRED_UNPUSHED_DLRS_SQL)) {
                // Rebinding also locks tracked messages before correlations; retain the same order to avoid deadlocks.
                messages.executeUpdate();
                correlations.executeUpdate();
                try (ResultSet resultSet = unpushedDlrs.executeQuery()) {
                    while (resultSet.next()) {
                        expiredUnpushedDlrKeys.put(
                                resultSet.getString("dlr_key"),
                                resultSet.getObject("generation_id", UUID.class));
                    }
                }
                connection.commit();
            } catch (SQLException e) {
                rollback(connection, e);
                throw e;
            }
        } catch (SQLException e) {
            throw failure("expire DLR state", e);
        }
        synchronized (unpushedDlrStateLock) {
            expiredUnpushedDlrKeys.forEach(claimedUnpushedDlrKeys::remove);
            HashSet<UUID> expiredGenerations = new HashSet<>(expiredUnpushedDlrKeys.values());
            claimedUnpushedDlrGenerations.entrySet()
                    .removeIf(entry -> expiredGenerations.contains(entry.getValue()));
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

    /**
     * Logs the database cause server-side and returns a caller-facing exception that carries no connection details.
     * The one-line summary keeps an outage diagnosable without a stack trace per rejected message; the full cause is
     * available at debug level.
     */
    private DlrStorageException failure(String operation, SQLException cause) {
        logger.error("Failed to {}: sqlState={} errorCode={} reason={}",
                operation, cause.getSQLState(), cause.getErrorCode(), cause.getMessage());
        logger.debug("DLR storage failure details while attempting to {}", operation, cause);
        return new DlrStorageException("Failed to " + operation, cause);
    }
}
