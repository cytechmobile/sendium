package gr.cytech.sendium.core.worker;

import gr.cytech.sendium.core.message.StandardMessage;

import javax.sql.DataSource;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class PostgresqlDlrStorage implements DlrStorage {
    private static final int DEFAULT_LINK_MAX_ATTEMPTS = 20;
    private static final long DEFAULT_LINK_RETRY_INTERVAL_MILLIS = 200;
    private static final long EXPIRY_CHECK_INTERVAL_MILLIS = TimeUnit.HOURS.toMillis(1);

    private static final String SAVE_INITIAL_STATE_SQL = """
            INSERT INTO sendium_dlr.tracked_message
                (gateway_message_id, account_id, system_id, source_address, destination_address,
                 operator_message_id, forward_dlr_url, reassembled_parts, status, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (gateway_message_id) DO UPDATE SET
                account_id = EXCLUDED.account_id,
                system_id = EXCLUDED.system_id,
                source_address = EXCLUDED.source_address,
                destination_address = EXCLUDED.destination_address,
                operator_message_id = EXCLUDED.operator_message_id,
                forward_dlr_url = EXCLUDED.forward_dlr_url,
                reassembled_parts = EXCLUDED.reassembled_parts,
                status = EXCLUDED.status,
                created_at = CURRENT_TIMESTAMP,
                updated_at = EXCLUDED.updated_at
            """;

    private static final String LINK_MESSAGE_SQL = """
            UPDATE sendium_dlr.tracked_message
            SET operator_message_id = ?, status = 'SENT', updated_at = CURRENT_TIMESTAMP
            WHERE gateway_message_id = ?
            """;

    private static final String SAVE_CORRELATION_SQL = """
            INSERT INTO sendium_dlr.operator_correlation
                (operator_message_id, gateway_message_id)
            VALUES (?, ?)
            ON CONFLICT (operator_message_id) DO UPDATE SET
                created_at = CURRENT_TIMESTAMP
            WHERE operator_correlation.gateway_message_id = EXCLUDED.gateway_message_id
            """;

    private static final String DELETE_CORRELATIONS_SQL = """
            DELETE FROM sendium_dlr.operator_correlation
            WHERE gateway_message_id = ?
            """;

    private static final String GET_STATE_SQL = """
            SELECT tm.gateway_message_id, tm.account_id, tm.system_id, tm.source_address,
                   tm.destination_address, tm.operator_message_id, tm.forward_dlr_url,
                   tm.reassembled_parts, tm.status, tm.updated_at
            FROM sendium_dlr.tracked_message tm
            WHERE tm.gateway_message_id = ?
            """;

    private static final String RESOLVE_STATE_SQL = """
            SELECT tm.gateway_message_id, tm.account_id, tm.system_id, tm.source_address,
                   tm.destination_address, tm.forward_dlr_url, tm.reassembled_parts,
                   correlation.operator_message_id, CURRENT_TIMESTAMP AS resolved_at
            FROM sendium_dlr.operator_correlation correlation
            JOIN sendium_dlr.tracked_message tm
                ON tm.gateway_message_id = correlation.gateway_message_id
            WHERE correlation.operator_message_id = ?
            FOR UPDATE OF tm, correlation
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
            DELETE FROM sendium_dlr.operator_correlation
            WHERE created_at < CURRENT_TIMESTAMP - INTERVAL '3 days'
            """;

    private static final String DELETE_EXPIRED_MESSAGES_SQL = """
            DELETE FROM sendium_dlr.tracked_message
            WHERE created_at < CURRENT_TIMESTAMP - INTERVAL '7 days'
            """;

    private static final String SAVE_UNPUSHED_DLR_SQL = """
            INSERT INTO sendium_dlr.unpushed_dlr
                (dlr_key, system_id, account_id, source_address, destination_address, serial,
                 message_id, dlr_state, error_code, acked, priority, reassembled_parts)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
                created_at = CURRENT_TIMESTAMP
            """;

    private static final String GET_UNPUSHED_DLRS_SQL = """
            SELECT dlr_key, system_id, account_id, source_address, destination_address, serial,
                   message_id, dlr_state, error_code, acked, priority, reassembled_parts
            FROM sendium_dlr.unpushed_dlr
            WHERE system_id = ?
            ORDER BY created_at, dlr_key
            """;

    private static final String DELETE_UNPUSHED_DLR_SQL = """
            DELETE FROM sendium_dlr.unpushed_dlr
            WHERE dlr_key = ?
            """;

    private static final String DELETE_EXPIRED_UNPUSHED_DLRS_SQL = """
            DELETE FROM sendium_dlr.unpushed_dlr
            WHERE created_at < CURRENT_TIMESTAMP - INTERVAL '7 days'
            RETURNING dlr_key
            """;

    private final DataSource dataSource;
    private final int linkMaxAttempts;
    private final long linkRetryIntervalMillis;
    private final long expiryCheckIntervalMillis;
    private final Object unpushedDlrStateLock = new Object();
    private final Set<String> claimedUnpushedDlrKeys = ConcurrentHashMap.newKeySet();
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
        Objects.requireNonNull(state, "state");
        checkExpiry();

        UUID gatewayMsgId = parseGatewayId(state.getGatewayMsgId());
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                saveState(connection, gatewayMsgId, state);
                deleteCorrelations(connection, gatewayMsgId);
                if (state.getOperatorMsgId() != null &&
                        !saveCorrelation(connection, gatewayMsgId, state.getOperatorMsgId())) {
                    throw new SQLException("Operator message ID is already linked to another gateway message");
                }
                connection.commit();
            } catch (SQLException e) {
                rollback(connection, e);
                throw e;
            }
        } catch (SQLException e) {
            throw failure("save initial DLR state", e);
        }
    }

    @Override
    public void linkOperatorId(String gatewayMsgId, String operatorMsgId) {
        checkExpiry();
        UUID gatewayId = parseGatewayId(gatewayMsgId);

        for (int attempt = 0; attempt < linkMaxAttempts; attempt++) {
            if (tryLinkOperatorId(gatewayId, operatorMsgId)) {
                return;
            }
            if (attempt + 1 < linkMaxAttempts) {
                sleepBeforeLinkRetry();
            }
        }
        throw new DlrStorageException("Gateway message state not found while linking operator ID");
    }

    @Override
    public Optional<MessageState> resolveAndRemoveDlr(String operatorMsgId, MessageState.MessageStatus status) {
        Objects.requireNonNull(status, "status");
        checkExpiry();

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                Optional<MessageState> state = lockResolvedState(connection, operatorMsgId, status);
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
                return resultSet.next() ? Optional.of(readState(resultSet)) : Optional.empty();
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
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(DELETE_UNPUSHED_DLR_SQL)) {
                statement.setString(1, key);
                boolean removed = statement.executeUpdate() == 1;
                claimedUnpushedDlrKeys.remove(key);
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
            claimedUnpushedDlrKeys.remove(getUnpushedDlrKey(message));
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
                        if (!claimForReplay || claimedUnpushedDlrKeys.add(key)) {
                            messages.add(readUnpushedDlr(resultSet).toMessage());
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

    private boolean tryLinkOperatorId(UUID gatewayMsgId, String operatorMsgId) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                if (!markAsSent(connection, gatewayMsgId, operatorMsgId)) {
                    connection.rollback();
                    return false;
                }
                if (!saveCorrelation(connection, gatewayMsgId, operatorMsgId)) {
                    throw new SQLException("Operator message ID is already linked to another gateway message");
                }
                connection.commit();
                return true;
            } catch (SQLException e) {
                rollback(connection, e);
                throw e;
            }
        } catch (SQLException e) {
            throw failure("link operator DLR ID", e);
        }
    }

    private boolean markAsSent(Connection connection, UUID gatewayMsgId,
                               String operatorMsgId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(LINK_MESSAGE_SQL)) {
            statement.setString(1, operatorMsgId);
            statement.setObject(2, gatewayMsgId);
            return statement.executeUpdate() == 1;
        }
    }

    private void saveState(Connection connection, UUID gatewayMsgId,
                           MessageState state) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SAVE_INITIAL_STATE_SQL)) {
            statement.setObject(1, gatewayMsgId);
            statement.setString(2, state.getAccountId());
            statement.setString(3, state.getSystemId());
            statement.setString(4, state.getSourceAddr());
            statement.setString(5, state.getDestAddr());
            statement.setString(6, state.getOperatorMsgId());
            statement.setString(7, state.getForwardDlrUrl());
            setStringArray(connection, statement, 8, state.getReassembledParts());
            statement.setString(9, state.getStatus().name());
            statement.setTimestamp(10, new Timestamp(state.getTimestamp()));
            statement.executeUpdate();
        }
    }

    private void deleteCorrelations(Connection connection, UUID gatewayMsgId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(DELETE_CORRELATIONS_SQL)) {
            statement.setObject(1, gatewayMsgId);
            statement.executeUpdate();
        }
    }

    private boolean saveCorrelation(Connection connection, UUID gatewayMsgId,
                                    String operatorMsgId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SAVE_CORRELATION_SQL)) {
            statement.setString(1, operatorMsgId);
            statement.setObject(2, gatewayMsgId);
            return statement.executeUpdate() == 1;
        }
    }

    private Optional<MessageState> lockResolvedState(Connection connection, String operatorMsgId,
                                                     MessageState.MessageStatus status) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(RESOLVE_STATE_SQL)) {
            statement.setString(1, operatorMsgId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                MessageState state = readState(resultSet);
                state.setOperatorMsgId(resultSet.getString("operator_message_id"));
                state.setStatus(status);
                state.setTimestamp(resultSet.getTimestamp("resolved_at").getTime());
                return Optional.of(state);
            }
        }
    }

    private void deleteState(Connection connection, UUID gatewayMsgId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(DELETE_STATE_SQL)) {
            statement.setObject(1, gatewayMsgId);
            statement.executeUpdate();
        }
    }

    private MessageState readState(ResultSet resultSet) throws SQLException {
        MessageState state = new MessageState(
                resultSet.getObject("gateway_message_id", UUID.class).toString(),
                resultSet.getString("account_id"),
                resultSet.getString("system_id"),
                resultSet.getString("source_address"),
                resultSet.getString("destination_address"),
                resultSet.getString("forward_dlr_url"));
        state.setOperatorMsgId(resultSet.getString("operator_message_id"));
        if (hasColumn(resultSet, "status")) {
            state.setStatus(MessageState.MessageStatus.valueOf(resultSet.getString("status")));
        }
        state.setReassembledParts(readStringArray(resultSet, "reassembled_parts"));
        if (hasColumn(resultSet, "updated_at")) {
            state.setTimestamp(resultSet.getTimestamp("updated_at").getTime());
        }
        return state;
    }

    private boolean hasColumn(ResultSet resultSet, String columnName) throws SQLException {
        for (int index = 1; index <= resultSet.getMetaData().getColumnCount(); index++) {
            if (columnName.equalsIgnoreCase(resultSet.getMetaData().getColumnLabel(index))) {
                return true;
            }
        }
        return false;
    }

    private List<String> readStringArray(ResultSet resultSet, String columnName) throws SQLException {
        Array array = resultSet.getArray(columnName);
        if (array == null) {
            return null;
        }
        return new ArrayList<>(List.of((String[]) array.getArray()));
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
            throw new DlrStorageException("Interrupted while linking operator ID", e);
        }
    }

    private void checkExpiry() {
        long now = System.currentTimeMillis();
        if (now - lastExpiryCheck < expiryCheckIntervalMillis) {
            return;
        }
        synchronized (this) {
            if (now - lastExpiryCheck < expiryCheckIntervalMillis) {
                return;
            }
            deleteExpiredState();
            lastExpiryCheck = now;
        }
    }

    private void deleteExpiredState() {
        synchronized (unpushedDlrStateLock) {
            List<String> expiredUnpushedDlrKeys = new ArrayList<>();
            try (Connection connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                try (PreparedStatement correlations = connection.prepareStatement(DELETE_EXPIRED_CORRELATIONS_SQL);
                     PreparedStatement messages = connection.prepareStatement(DELETE_EXPIRED_MESSAGES_SQL);
                     PreparedStatement unpushedDlrs = connection.prepareStatement(DELETE_EXPIRED_UNPUSHED_DLRS_SQL)) {
                    correlations.executeUpdate();
                    messages.executeUpdate();
                    try (ResultSet resultSet = unpushedDlrs.executeQuery()) {
                        while (resultSet.next()) {
                            expiredUnpushedDlrKeys.add(resultSet.getString("dlr_key"));
                        }
                    }
                    connection.commit();
                    claimedUnpushedDlrKeys.removeAll(expiredUnpushedDlrKeys);
                } catch (SQLException e) {
                    rollback(connection, e);
                    throw e;
                }
            } catch (SQLException e) {
                throw failure("expire DLR state", e);
            }
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
        return new DlrStorageException("Failed to " + operation, cause);
    }
}
