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
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class PostgresqlDlrStorage implements DlrStorage {
    private static final Logger logger = LoggerFactory.getLogger(PostgresqlDlrStorage.class);

    private static final long EXPIRY_CHECK_INTERVAL_MILLIS = TimeUnit.HOURS.toMillis(1);
    private static final Duration DEFAULT_DELIVERY_CLAIM_DURATION = Duration.ofMinutes(5);
    private static final int MAX_DELIVERY_BATCH_SIZE = 1_000;
    private static final int MAX_BATCH_ATTEMPTS = 3;

    private static final String STATE_COLUMNS = """
            gateway_message_id, account_id, system_id, source_address, destination_address,
            provider_name, provider_message_id, forward_dlr_url, reassembled_parts, provider_status,
            dlr_state, error_code, delivery_channel, delivery_status, delivery_attempt_count,
            last_attempt_at, next_attempt_at, last_delivery_result, resolved_at, updated_at, dlr_mask
            """;

    private static final String RECORD_PROVIDER_ACCEPTED_SQL = """
            WITH accepted AS (
                INSERT INTO sendium_dlr.dlr_message
                    (gateway_message_id, account_id, system_id, source_address, destination_address,
                     provider_name, provider_message_id, forward_dlr_url, reassembled_parts, provider_status,
                     dlr_state, error_code, delivery_channel, delivery_status, delivery_attempt_count,
                     last_attempt_at, next_attempt_at, last_delivery_result, resolved_at, updated_at, dlr_mask)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (gateway_message_id) DO UPDATE SET
                    account_id = EXCLUDED.account_id,
                    system_id = EXCLUDED.system_id,
                    source_address = EXCLUDED.source_address,
                    destination_address = EXCLUDED.destination_address,
                    provider_name = EXCLUDED.provider_name,
                    provider_message_id = EXCLUDED.provider_message_id,
                    forward_dlr_url = EXCLUDED.forward_dlr_url,
                    dlr_mask = EXCLUDED.dlr_mask,
                    reassembled_parts = EXCLUDED.reassembled_parts,
                    provider_status = EXCLUDED.provider_status,
                    updated_at = EXCLUDED.updated_at
                WHERE dlr_message.delivery_status = 'WAITING_PROVIDER'
                RETURNING gateway_message_id
            ), saved_correlation AS (
                INSERT INTO sendium_dlr.provider_correlation
                    (provider_name, provider_message_id, gateway_message_id)
                SELECT ?, ?, gateway_message_id FROM accepted
                ON CONFLICT (provider_name, provider_message_id) DO UPDATE SET
                    gateway_message_id = EXCLUDED.gateway_message_id,
                    created_at = CURRENT_TIMESTAMP
                RETURNING gateway_message_id
            ), cleared_previous_owner AS (
                UPDATE sendium_dlr.dlr_message
                SET provider_name = NULL, provider_message_id = NULL, updated_at = CURRENT_TIMESTAMP
                WHERE provider_name = ? AND provider_message_id = ?
                  AND gateway_message_id <> (SELECT gateway_message_id FROM accepted)
                  AND delivery_status = 'WAITING_PROVIDER'
                  AND EXISTS (SELECT 1 FROM saved_correlation)
            )
            SELECT EXISTS (SELECT 1 FROM accepted)
            """;

    private static final String RECORD_PROVIDER_REJECTED_SQL = """
            INSERT INTO sendium_dlr.dlr_message
                (gateway_message_id, account_id, system_id, source_address, destination_address,
                 provider_name, provider_message_id, forward_dlr_url, reassembled_parts, provider_status,
                 dlr_state, error_code, delivery_channel, delivery_status, delivery_attempt_count,
                 last_attempt_at, next_attempt_at, last_delivery_result, resolved_at, updated_at, dlr_mask)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (gateway_message_id) DO UPDATE SET
                account_id = EXCLUDED.account_id,
                system_id = EXCLUDED.system_id,
                source_address = EXCLUDED.source_address,
                destination_address = EXCLUDED.destination_address,
                provider_name = EXCLUDED.provider_name,
                provider_message_id = EXCLUDED.provider_message_id,
                forward_dlr_url = EXCLUDED.forward_dlr_url,
                dlr_mask = EXCLUDED.dlr_mask,
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
                updated_at = EXCLUDED.updated_at
            WHERE dlr_message.delivery_status = 'WAITING_PROVIDER'
            RETURNING %s
            """.formatted(STATE_COLUMNS);

    private static final String LOCK_MESSAGE_SQL = """
            SELECT 1
            FROM sendium_dlr.dlr_message
            WHERE gateway_message_id = ?
            FOR UPDATE
            """;

    private static final String LOCK_CORRELATION_SQL = """
            SELECT pg_advisory_xact_lock(hashtextextended(?, 0) # hashtextextended(?, 1))
            """;

    private static final String LOCK_CORRELATED_MESSAGES_SQL = """
            SELECT COUNT(*) FROM (
                SELECT gateway_message_id
                FROM sendium_dlr.dlr_message
                WHERE gateway_message_id = ? OR gateway_message_id = (
                    SELECT gateway_message_id FROM sendium_dlr.provider_correlation
                    WHERE provider_name = ? AND provider_message_id = ?
                )
                ORDER BY gateway_message_id
                FOR UPDATE
            ) AS locked_messages
            """;

    private static final String LOCK_CORRELATION_OWNER_SQL = """
            SELECT gateway_message_id FROM sendium_dlr.dlr_message
            WHERE gateway_message_id = (
                SELECT gateway_message_id FROM sendium_dlr.provider_correlation
                WHERE provider_name = ? AND provider_message_id = ?
            )
            FOR UPDATE
            """;

    private static final String LOCK_BATCH_CORRELATIONS_SQL = """
            SELECT COUNT(pg_advisory_xact_lock(lock_key)) FROM (
                SELECT DISTINCT hashtextextended(provider_name, 0) # hashtextextended(provider_message_id, 1) AS lock_key
                FROM unnest(?::text[], ?::text[]) AS requested(provider_name, provider_message_id)
                ORDER BY lock_key
            ) AS ordered_keys
            """;

    private static final String LOCK_BATCH_MESSAGES_SQL = """
            SELECT COUNT(*) FROM (
                SELECT gateway_message_id FROM sendium_dlr.dlr_message
                WHERE gateway_message_id = ANY(?::uuid[]) OR gateway_message_id IN (
                    SELECT correlation.gateway_message_id FROM sendium_dlr.provider_correlation correlation
                    JOIN unnest(?::text[], ?::text[]) AS requested(provider_name, provider_message_id)
                      USING (provider_name, provider_message_id)
                )
                ORDER BY gateway_message_id FOR UPDATE
            ) AS locked_messages
            """;

    private static final String BATCH_CORRELATION_OWNERS_SQL = """
            SELECT correlation.provider_name, correlation.provider_message_id, correlation.gateway_message_id
            FROM sendium_dlr.provider_correlation correlation
            JOIN unnest(?::text[], ?::text[]) AS requested(provider_name, provider_message_id)
              USING (provider_name, provider_message_id)
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
            WITH resolved AS (
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
            ), consumed_correlations AS (
                DELETE FROM sendium_dlr.provider_correlation
                WHERE gateway_message_id IN (SELECT gateway_message_id FROM resolved)
            )
            SELECT %s FROM resolved
            """.formatted(STATE_COLUMNS, STATE_COLUMNS);

    private static final String LIST_PENDING_SMPP_SQL = """
            SELECT %s
            FROM sendium_dlr.dlr_message
            WHERE system_id = ? AND delivery_channel = 'SMPP' AND delivery_status = 'PENDING'
            ORDER BY resolved_at, created_at, gateway_message_id
            """.formatted(STATE_COLUMNS);

    private static final String CLAIM_DUE_HTTP_SQL = """
            WITH due AS (
                SELECT gateway_message_id AS due_gateway_message_id
                FROM sendium_dlr.dlr_message
                WHERE delivery_channel = 'HTTP' AND delivery_status = 'PENDING'
                  AND next_attempt_at <= CURRENT_TIMESTAMP
                  AND (claimed_until IS NULL OR claimed_until <= CURRENT_TIMESTAMP)
                ORDER BY next_attempt_at, gateway_message_id
                LIMIT ?
                FOR UPDATE SKIP LOCKED
            )
            UPDATE sendium_dlr.dlr_message message
            SET delivery_attempt_count = message.delivery_attempt_count + 1,
                last_attempt_at = CURRENT_TIMESTAMP,
                claimed_until = CURRENT_TIMESTAMP + (? * INTERVAL '1 millisecond'),
                updated_at = CURRENT_TIMESTAMP
            FROM due
            WHERE message.gateway_message_id = due.due_gateway_message_id
            RETURNING %s
            """.formatted(STATE_COLUMNS);

    private static final String START_DELIVERY_SQL = """
            UPDATE sendium_dlr.dlr_message
            SET delivery_attempt_count = delivery_attempt_count + 1,
                last_attempt_at = CURRENT_TIMESTAMP,
                claimed_until = CURRENT_TIMESTAMP + (? * INTERVAL '1 millisecond'),
                updated_at = CURRENT_TIMESTAMP
            WHERE gateway_message_id = ? AND delivery_channel = ? AND delivery_status = 'PENDING'
              AND (claimed_until IS NULL OR claimed_until <= CURRENT_TIMESTAMP)
            RETURNING %s
            """.formatted(STATE_COLUMNS);

    private static final String COMPLETE_DELIVERY_SQL = """
            DELETE FROM sendium_dlr.dlr_message
            WHERE gateway_message_id = ? AND delivery_status = 'PENDING' AND delivery_attempt_count = ?
            """;

    private static final String RETRY_DELIVERY_SQL = """
            UPDATE sendium_dlr.dlr_message
            SET last_delivery_result = ?, next_attempt_at = ?, claimed_until = NULL,
                updated_at = CURRENT_TIMESTAMP
            WHERE gateway_message_id = ? AND delivery_status = 'PENDING' AND delivery_attempt_count = ?
            """;

    private static final String FAIL_DELIVERY_SQL = """
            UPDATE sendium_dlr.dlr_message
            SET delivery_status = 'FAILED', last_delivery_result = ?, next_attempt_at = NULL, claimed_until = NULL,
                updated_at = CURRENT_TIMESTAMP
            WHERE gateway_message_id = ? AND delivery_status = 'PENDING' AND delivery_attempt_count = ?
            """;

    private static final String FAIL_INVALID_DELIVERY_SQL = """
            UPDATE sendium_dlr.dlr_message
            SET delivery_status = 'FAILED', last_delivery_result = ?, next_attempt_at = NULL, claimed_until = NULL,
                resolved_at = COALESCE(resolved_at, CURRENT_TIMESTAMP), updated_at = CURRENT_TIMESTAMP
            WHERE gateway_message_id = ? AND delivery_status = 'PENDING' AND delivery_attempt_count = ?
            """;

    private static final String DELETE_EXPIRED_CORRELATIONS_SQL = """
            DELETE FROM sendium_dlr.provider_correlation
            WHERE created_at < CURRENT_TIMESTAMP - INTERVAL '7 days'
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
    private final long deliveryClaimDurationMillis;
    private final long expiryCheckIntervalMillis;
    private final AtomicBoolean expiryInProgress = new AtomicBoolean();
    private volatile long lastExpiryCheck;

    public PostgresqlDlrStorage(DataSource dataSource) {
        this(dataSource, DEFAULT_DELIVERY_CLAIM_DURATION, EXPIRY_CHECK_INTERVAL_MILLIS);
    }

    PostgresqlDlrStorage(DataSource dataSource, long expiryCheckIntervalMillis) {
        this(dataSource, DEFAULT_DELIVERY_CLAIM_DURATION, expiryCheckIntervalMillis);
    }

    PostgresqlDlrStorage(DataSource dataSource, Duration deliveryClaimDuration) {
        this(dataSource, deliveryClaimDuration, EXPIRY_CHECK_INTERVAL_MILLIS);
    }

    private PostgresqlDlrStorage(DataSource dataSource, Duration deliveryClaimDuration,
                                 long expiryCheckIntervalMillis) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        Objects.requireNonNull(deliveryClaimDuration, "deliveryClaimDuration");
        long claimDurationMillis = deliveryClaimDuration.toMillis();
        if (claimDurationMillis < 1) {
            throw new IllegalArgumentException("Delivery claim duration must be positive");
        }
        if (expiryCheckIntervalMillis < 0) {
            throw new IllegalArgumentException("Invalid expiry policy");
        }
        this.deliveryClaimDurationMillis = claimDurationMillis;
        this.expiryCheckIntervalMillis = expiryCheckIntervalMillis;
    }

    private UUID prepareAccepted(MessageState state, String providerName, String providerMessageId) {
        Objects.requireNonNull(state, "state");
        requireCorrelation(providerName, providerMessageId);
        state.setProviderName(providerName);
        state.setProviderMessageId(providerMessageId);
        state.setStatus(MessageState.MessageStatus.SENT);
        state.setDeliveryStatus(MessageState.DeliveryStatus.WAITING_PROVIDER);
        state.setDlrState(null);
        state.setErrorCode(null);
        state.setLastAttemptAt(null);
        state.setNextAttemptAt(null);
        state.setLastDeliveryResult(null);
        state.setResolvedAt(null);
        state.setTimestamp(System.currentTimeMillis());
        validateState(state);
        return parseGatewayId(state.getGatewayMsgId());
    }

    @Override
    public void recordProviderAccepted(MessageState state, String providerName, String providerMessageId) {
        UUID gatewayMessageId = prepareAccepted(state, providerName, providerMessageId);
        checkExpiry();

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                lockCorrelation(connection, providerName, providerMessageId);
                lockCorrelatedMessages(connection, providerName, providerMessageId, gatewayMessageId);

                if (!persistAccepted(connection, gatewayMessageId, state, providerName, providerMessageId)) {
                    connection.rollback();
                    throw new DlrStorageException("Provider acceptance cannot replace terminal DLR state");
                }
                connection.commit();
            } catch (SQLException e) {
                rollback(connection, e);
                throw e;
            }
        } catch (SQLException e) {
            throw failure("record provider acceptance", e);
        }
    }

    private UUID prepareRejected(MessageState state, String providerName, String providerMessageId,
                                 int dlrState, String errorCode) {
        Objects.requireNonNull(state, "state");
        boolean hasProviderMessageId = providerMessageId != null && !providerMessageId.isBlank();
        if (hasProviderMessageId) {
            requireCorrelation(providerName, providerMessageId);
            state.setProviderName(providerName);
            state.setProviderMessageId(providerMessageId);
        } else {
            state.setProviderName(null);
            state.setProviderMessageId(null);
        }
        long now = System.currentTimeMillis();
        state.setStatus(MessageState.MessageStatus.FAILED);
        state.setDlrState(dlrState);
        state.setErrorCode(errorCode);
        state.setDeliveryStatus(MessageState.DeliveryStatus.PENDING);
        state.setDeliveryAttemptCount(0);
        state.setLastAttemptAt(null);
        state.setNextAttemptAt(state.getDeliveryChannel() == MessageState.DeliveryChannel.HTTP ? now : null);
        state.setLastDeliveryResult("provider_rejected");
        state.setResolvedAt(now);
        state.setTimestamp(now);
        validateState(state);
        return parseGatewayId(state.getGatewayMsgId());
    }

    @Override
    public Optional<MessageState> recordProviderRejected(MessageState state, String providerName,
                                                         String providerMessageId, int dlrState,
                                                         String errorCode) {
        UUID gatewayMessageId = prepareRejected(state, providerName, providerMessageId, dlrState, errorCode);
        checkExpiry();

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                lockMessage(connection, gatewayMessageId);
                Optional<MessageState> rejected = persistRejected(connection, gatewayMessageId, state);
                if (rejected.isEmpty()) {
                    connection.rollback();
                    return Optional.empty();
                }
                deleteCorrelations(connection, gatewayMessageId);
                connection.commit();
                return rejected;
            } catch (SQLException e) {
                rollback(connection, e);
                throw e;
            }
        } catch (SQLException e) {
            throw failure("record provider rejection", e);
        }
    }

    public List<ProviderDlrEvent.Result> applyProviderBatch(List<ProviderDlrEvent> events) {
        events = List.copyOf(events);
        for (int attempt = 1; ; attempt++) {
            try {
                return applyProviderBatchOnce(events);
            } catch (DlrStorageException e) {
                if (attempt >= MAX_BATCH_ATTEMPTS || !(e.getCause() instanceof SQLException sql) ||
                        !"40P01".equals(sql.getSQLState())) {
                    throw e;
                }
                logger.warn("Retrying rolled-back provider batch after PostgreSQL deadlock; attempt={}", attempt + 1);
            }
        }
    }

    private List<ProviderDlrEvent.Result> applyProviderBatchOnce(List<ProviderDlrEvent> events) {
        if (events.isEmpty()) {
            return List.of();
        }
        if (events.size() > MAX_DELIVERY_BATCH_SIZE) {
            throw new IllegalArgumentException("Provider batch exceeds maximum size");
        }
        var keys = new LinkedHashSet<CorrelationKey>();
        var messageIds = new LinkedHashSet<UUID>();
        for (ProviderDlrEvent event : events) {
            if (event.providerMessageId() != null && !event.providerMessageId().isBlank()) {
                keys.add(new CorrelationKey(event.providerName(), event.providerMessageId()));
            }
            if (event instanceof ProviderDlrEvent.Accepted accepted) {
                messageIds.add(prepareAccepted(
                        accepted.message(), accepted.providerName(), accepted.providerMessageId()));
            } else if (event instanceof ProviderDlrEvent.Rejected rejected) {
                messageIds.add(prepareRejected(
                        rejected.message(), rejected.providerName(), rejected.providerMessageId(),
                        rejected.dlrState(), rejected.errorCode()));
            }
        }
        checkExpiry();
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                Map<CorrelationKey, UUID> owners = lockProviderBatch(connection, List.copyOf(keys), List.copyOf(messageIds));
                List<ProviderDlrEvent.Result> results = new ArrayList<>(events.size());
                for (ProviderDlrEvent event : events) {
                    results.add(applyProviderEvent(connection, event, owners));
                }
                connection.commit();
                return List.copyOf(results);
            } catch (SQLException e) {
                rollback(connection, e);
                throw e;
            } catch (RuntimeException e) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    e.addSuppressed(rollbackFailure);
                }
                throw e;
            }
        } catch (SQLException e) {
            throw failure("persist provider batch", e);
        }
    }

    private ProviderDlrEvent.Result applyProviderEvent(Connection connection, ProviderDlrEvent event,
                                                       Map<CorrelationKey, UUID> owners) throws SQLException {
        CorrelationKey key = new CorrelationKey(event.providerName(), event.providerMessageId());
        Optional<MessageState> result;
        switch (event) {
            case ProviderDlrEvent.Accepted accepted -> {
                UUID messageId = parseGatewayId(accepted.message().getGatewayMsgId());
                if (!persistAccepted(connection, messageId, accepted.message(), key.provider(), key.messageId())) {
                    return ProviderDlrEvent.Result.rejectedAcceptance();
                }
                owners.put(key, messageId);
                return ProviderDlrEvent.Result.success(Optional.empty());
            }
            case ProviderDlrEvent.Rejected rejected -> {
                UUID messageId = parseGatewayId(rejected.message().getGatewayMsgId());
                result = persistRejected(connection, messageId, rejected.message());
                if (result.isPresent()) {
                    deleteCorrelations(connection, messageId);
                    owners.values().removeIf(messageId::equals);
                }
            }
            case ProviderDlrEvent.Receipt receipt -> {
                UUID messageId = owners.get(key);
                result = messageId == null ? Optional.empty() : resolveState(connection, messageId,
                        key.provider(), key.messageId(), receipt.status(), receipt.dlrState(), receipt.errorCode());
                if (result.isPresent()) {
                    owners.values().removeIf(messageId::equals);
                }
            }
        }
        return ProviderDlrEvent.Result.success(result);
    }

    private Map<CorrelationKey, UUID> lockProviderBatch(Connection connection, List<CorrelationKey> keys,
                                                       List<UUID> messageIds) throws SQLException {
        Array providers = connection.createArrayOf("text", keys.stream().map(CorrelationKey::provider).toArray());
        Array providerIds = connection.createArrayOf("text", keys.stream().map(CorrelationKey::messageId).toArray());
        Array gatewayIds = connection.createArrayOf("uuid", messageIds.toArray());
        try {
            try (PreparedStatement statement = connection.prepareStatement(LOCK_BATCH_CORRELATIONS_SQL)) {
                statement.setArray(1, providers);
                statement.setArray(2, providerIds);
                statement.execute();
            }
            try (PreparedStatement statement = connection.prepareStatement(LOCK_BATCH_MESSAGES_SQL)) {
                statement.setArray(1, gatewayIds);
                statement.setArray(2, providers);
                statement.setArray(3, providerIds);
                statement.execute();
            }
            // Read owners after row-lock waits, not from the locking statement's older snapshot.
            try (PreparedStatement statement = connection.prepareStatement(BATCH_CORRELATION_OWNERS_SQL)) {
                statement.setArray(1, providers);
                statement.setArray(2, providerIds);
                try (ResultSet rows = statement.executeQuery()) {
                    Map<CorrelationKey, UUID> owners = new HashMap<>();
                    while (rows.next()) {
                        owners.put(new CorrelationKey(rows.getString(1), rows.getString(2)), rows.getObject(3, UUID.class));
                    }
                    return owners;
                }
            }
        } finally {
            providers.free();
            providerIds.free();
            gatewayIds.free();
        }
    }

    private boolean persistAccepted(Connection connection, UUID messageId, MessageState state,
                                     String provider, String providerId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(RECORD_PROVIDER_ACCEPTED_SQL)) {
            setStateParameters(connection, statement, messageId, state);
            statement.setString(22, provider);
            statement.setString(23, providerId);
            statement.setString(24, provider);
            statement.setString(25, providerId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() && rows.getBoolean(1);
            }
        }
    }

    private Optional<MessageState> persistRejected(Connection connection, UUID messageId, MessageState state) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(RECORD_PROVIDER_REJECTED_SQL)) {
            setStateParameters(connection, statement, messageId, state);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(readState(rows)) : Optional.empty();
            }
        }
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
                Optional<UUID> gatewayMessageId = lockCorrelationOwner(connection, providerName, providerMessageId);
                if (gatewayMessageId.isEmpty()) {
                    connection.rollback();
                    return Optional.empty();
                }
                Optional<MessageState> state = resolveState(connection, gatewayMessageId.get(), providerName,
                        providerMessageId, status, dlrState, errorCode);
                if (state.isEmpty()) {
                    connection.rollback();
                    return Optional.empty();
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
    public List<MessageState> claimDueHttpDeliveries(int limit) {
        checkExpiry();
        if (limit < 1) {
            throw new IllegalArgumentException("Delivery limit must be positive");
        }
        int boundedLimit = Math.min(limit, MAX_DELIVERY_BATCH_SIZE);
        return listStates(CLAIM_DUE_HTTP_SQL, statement -> {
            statement.setInt(1, boundedLimit);
            statement.setLong(2, deliveryClaimDurationMillis);
        }, "claim due HTTP deliveries");
    }

    @Override
    public Optional<MessageState> startDeliveryAttempt(String gatewayMsgId,
                                                       MessageState.DeliveryChannel expectedChannel) {
        Objects.requireNonNull(expectedChannel, "expectedChannel");
        if (expectedChannel == MessageState.DeliveryChannel.NONE) {
            throw new IllegalArgumentException("A delivery attempt requires HTTP or SMPP channel");
        }

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(START_DELIVERY_SQL)) {
            statement.setLong(1, deliveryClaimDurationMillis);
            statement.setObject(2, parseGatewayId(gatewayMsgId));
            statement.setString(3, expectedChannel.name());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(readState(resultSet)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw failure("start DLR delivery attempt", e);
        }
    }

    @Override
    public boolean completeDelivery(String gatewayMsgId, int expectedAttempt) {
        return finishAttempt(expectedAttempt, COMPLETE_DELIVERY_SQL,
                statement -> {
                    statement.setObject(1, parseGatewayId(gatewayMsgId));
                    statement.setInt(2, expectedAttempt);
                }, "complete DLR delivery");
    }

    @Override
    public boolean retryDelivery(String gatewayMsgId, int expectedAttempt, String result, long nextAttemptAt) {
        return finishAttempt(expectedAttempt, RETRY_DELIVERY_SQL,
                statement -> {
                    statement.setString(1, normalizeResult(result));
                    statement.setObject(2, toOffsetDateTime(nextAttemptAt));
                    statement.setObject(3, parseGatewayId(gatewayMsgId));
                    statement.setInt(4, expectedAttempt);
                }, "retry DLR delivery");
    }

    @Override
    public boolean failDelivery(String gatewayMsgId, int expectedAttempt, String result) {
        return finishAttempt(expectedAttempt, FAIL_DELIVERY_SQL,
                statement -> {
                    statement.setString(1, normalizeResult(result));
                    statement.setObject(2, parseGatewayId(gatewayMsgId));
                    statement.setInt(3, expectedAttempt);
                }, "fail DLR delivery");
    }

    @Override
    public boolean failInvalidDelivery(String gatewayMsgId, int expectedAttempt, String result) {
        if (expectedAttempt < 1) {
            throw new IllegalArgumentException("Expected attempt must be positive");
        }
        checkExpiry();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(FAIL_INVALID_DELIVERY_SQL)) {
            statement.setString(1, normalizeResult(result));
            statement.setObject(2, parseGatewayId(gatewayMsgId));
            statement.setInt(3, expectedAttempt);
            return statement.executeUpdate() == 1;
        } catch (SQLException e) {
            throw failure("mark invalid DLR delivery failed", e);
        }
    }

    private boolean finishAttempt(int expectedAttempt, String sql,
                                  StatementBinder binder, String operation) {
        if (expectedAttempt < 1) {
            throw new IllegalArgumentException("Expected attempt must be positive");
        }
        checkExpiry();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            return statement.executeUpdate() == 1;
        } catch (SQLException e) {
            throw failure(operation, e);
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

    private void lockCorrelation(Connection connection, String providerName,
                                 String providerMessageId) throws SQLException {
        // Keep this separate so the following statement takes a fresh snapshot after any lock wait.
        try (PreparedStatement statement = connection.prepareStatement(LOCK_CORRELATION_SQL)) {
            statement.setString(1, providerName);
            statement.setString(2, providerMessageId);
            statement.execute();
        }
    }

    private void lockCorrelatedMessages(Connection connection, String providerName,
                                        String providerMessageId, UUID gatewayMessageId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(LOCK_CORRELATED_MESSAGES_SQL)) {
            statement.setObject(1, gatewayMessageId);
            statement.setString(2, providerName);
            statement.setString(3, providerMessageId);
            statement.execute();
        }
    }

    private Optional<UUID> lockCorrelationOwner(Connection connection, String providerName,
                                                String providerMessageId) throws SQLException {
        // A fresh mutation snapshot must include correlations committed while waiting for the message lock.
        try (PreparedStatement statement = connection.prepareStatement(LOCK_CORRELATION_OWNER_SQL)) {
            statement.setString(1, providerName);
            statement.setString(2, providerMessageId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(resultSet.getObject(1, UUID.class)) : Optional.empty();
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
        statement.setInt(21, state.getDlrMask());
    }

    private MessageState readState(ResultSet resultSet) throws SQLException {
        MessageState state = new MessageState(
                resultSet.getObject("gateway_message_id", UUID.class).toString(),
                resultSet.getString("account_id"),
                resultSet.getString("system_id"),
                resultSet.getString("source_address"),
                resultSet.getString("destination_address"),
                resultSet.getString("forward_dlr_url"));
        state.setDlrMask(resultSet.getInt("dlr_mask"));
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
        if (state.getDeliveryChannel() == MessageState.DeliveryChannel.NONE) {
            throw new IllegalArgumentException("DLR persistence requires an HTTP or SMPP delivery target");
        }
        if (state.getDeliveryChannel() == MessageState.DeliveryChannel.HTTP &&
                (state.getForwardDlrUrl() == null || state.getForwardDlrUrl().isBlank())) {
            throw new IllegalArgumentException("HTTP delivery requires a nonblank callback URL");
        }
        if (state.getDeliveryChannel() == MessageState.DeliveryChannel.SMPP &&
                (state.getSystemId() == null || state.getSystemId().isBlank())) {
            throw new IllegalArgumentException("SMPP delivery requires a nonblank system ID");
        }
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

    private record CorrelationKey(String provider, String messageId) { }

    @FunctionalInterface
    private interface StatementBinder {
        void bind(PreparedStatement statement) throws SQLException;
    }
}
