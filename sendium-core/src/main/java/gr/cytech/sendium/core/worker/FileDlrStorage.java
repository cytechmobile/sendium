package gr.cytech.sendium.core.worker;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import gr.cytech.sendium.core.message.DlrReturnMetadata.HttpCallbackFormat;
import gr.cytech.sendium.util.MessageTrace;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Local SQLite DLR storage owned by one process. Every operation runs under one monitor, so readers never observe
 * a partially applied transaction. Not selectable yet; provider acceptance, terminal outcomes, batches and
 * fenced delivery attempts are implemented; retention and metric snapshots are not.
 * Underlying file corruption is not guaranteed to be detected (accepted file-storage limitation).
 */
final class FileDlrStorage implements AutoCloseable {
    static final String DATABASE_FILE = "dlr.db";
    static final String LOCK_FILE = "owner.lock";
    static final int APPLICATION_ID = 0x53444C52; // "SDLR"
    static final int SCHEMA_VERSION = 1;
    static final String CORRELATION_OWNER_SQL =
            "SELECT gateway_message_id FROM provider_correlation WHERE provider_name = ? AND provider_message_id = ?";
    static final String CLEAR_PREVIOUS_OWNER_SQL = """
            UPDATE dlr_message SET provider_name = NULL, provider_message_id = NULL, updated_at = ?
            WHERE provider_name = ? AND provider_message_id = ? AND gateway_message_id <> ?
              AND delivery_status = 'WAITING_PROVIDER'
            """;
    static final String LIST_PENDING_SMPP_SQL = """
            SELECT gateway_message_id FROM dlr_message
            WHERE system_id = ? AND delivery_channel = 'SMPP' AND delivery_status = 'PENDING'
            ORDER BY resolved_at, created_at, gateway_message_id
            """;
    // Live leases inside the due range are skipped while scanning the due index.
    static final String DUE_HTTP_SQL = """
            SELECT gateway_message_id FROM dlr_message
            WHERE delivery_channel = 'HTTP' AND delivery_status = 'PENDING' AND next_attempt_at <= ?
              AND (claimed_until IS NULL OR claimed_until <= ?)
            ORDER BY next_attempt_at, gateway_message_id
            LIMIT ?
            """;

    private static final int SQLITE_CONSTRAINT = 19;
    private static final int MAX_BATCH_SIZE = 1_000;
    private static final Duration DEFAULT_CLAIM_DURATION = Duration.ofMinutes(5);
    private static final Set<String> REQUIRED_OBJECTS = Set.of(
            "dlr_meta", "dlr_message", "provider_correlation", "provider_correlation_gateway_idx",
            "dlr_message_provider_idx", "dlr_message_http_due_idx", "dlr_message_smpp_replay_idx");
    private static final List<String> SCHEMA = List.of("""
            CREATE TABLE dlr_meta (
                meta_key TEXT PRIMARY KEY,
                meta_value TEXT NOT NULL
            ) STRICT
            """, """
            CREATE TABLE dlr_message (
                gateway_message_id TEXT PRIMARY KEY,
                account_id TEXT,
                system_id TEXT,
                source_address TEXT,
                destination_address TEXT,
                provider_name TEXT,
                provider_message_id TEXT,
                forward_dlr_url TEXT,
                http_callback_format TEXT NOT NULL CHECK (http_callback_format IN ('KANNEL_GET', 'JSON_POST')),
                dlr_mask INTEGER NOT NULL CHECK (dlr_mask BETWEEN 0 AND 3),
                reassembled_parts TEXT,
                provider_status TEXT NOT NULL CHECK (provider_status IN ('SENT', 'DELIVERED', 'FAILED')),
                dlr_state INTEGER,
                error_code TEXT,
                delivery_channel TEXT NOT NULL CHECK (delivery_channel IN ('HTTP', 'SMPP')),
                delivery_status TEXT NOT NULL CHECK (delivery_status IN ('WAITING_PROVIDER', 'PENDING', 'FAILED')),
                delivery_attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (delivery_attempt_count >= 0),
                last_attempt_at INTEGER,
                next_attempt_at INTEGER,
                claimed_until INTEGER,
                last_delivery_result TEXT,
                resolved_at INTEGER,
                failed_at INTEGER,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                CHECK ((provider_name IS NULL) = (provider_message_id IS NULL))
            ) STRICT
            """, """
            CREATE TABLE provider_correlation (
                provider_name TEXT NOT NULL,
                provider_message_id TEXT NOT NULL,
                gateway_message_id TEXT NOT NULL REFERENCES dlr_message (gateway_message_id) ON DELETE CASCADE,
                created_at INTEGER NOT NULL,
                PRIMARY KEY (provider_name, provider_message_id)
            ) STRICT
            """, """
            CREATE INDEX provider_correlation_gateway_idx ON provider_correlation (gateway_message_id)
            """, """
            CREATE INDEX dlr_message_provider_idx ON dlr_message (provider_name, provider_message_id)
            WHERE provider_name IS NOT NULL
            """, """
            CREATE INDEX dlr_message_http_due_idx ON dlr_message (next_attempt_at, gateway_message_id)
            WHERE delivery_channel = 'HTTP' AND delivery_status = 'PENDING'
            """, """
            CREATE INDEX dlr_message_smpp_replay_idx ON dlr_message (system_id, resolved_at, created_at, gateway_message_id)
            WHERE delivery_channel = 'SMPP' AND delivery_status = 'PENDING'
            """);
    private static final String START_ATTEMPT_SQL = """
            UPDATE dlr_message SET delivery_attempt_count = delivery_attempt_count + 1, last_attempt_at = ?,
                claimed_until = ?, updated_at = ?
            WHERE gateway_message_id = ? AND delivery_channel = ? AND delivery_status = 'PENDING'
              AND (claimed_until IS NULL OR claimed_until <= ?)
            """;
    private static final String STATE_COLUMNS = """
            gateway_message_id, account_id, system_id, source_address, destination_address, provider_name,
            provider_message_id, forward_dlr_url, http_callback_format, dlr_mask, reassembled_parts, provider_status,
            dlr_state, error_code, delivery_channel, delivery_status, delivery_attempt_count, last_attempt_at,
            next_attempt_at, last_delivery_result, resolved_at, updated_at
            """;
    private static final ObjectMapper JSON = new ObjectMapper();

    // Test seam: runs inside each write transaction just before commit.
    SqlHook beforeCommit = ignored -> { };
    private final Path directory;
    private final FileChannel ownerChannel;
    private final FileLock ownerLock;
    private final Clock clock;
    private final long claimDurationMillis;
    private Connection connection;
    private DlrStorageException unavailable;
    private boolean closed;

    private FileDlrStorage(Path directory, FileChannel ownerChannel, FileLock ownerLock, Clock clock,
                           long claimDurationMillis) {
        this.directory = directory;
        this.ownerChannel = ownerChannel;
        this.ownerLock = ownerLock;
        this.clock = clock;
        this.claimDurationMillis = claimDurationMillis;
    }

    static FileDlrStorage open(Path directory) {
        return open(directory, DEFAULT_CLAIM_DURATION, Clock.systemUTC());
    }

    // Takes exclusive ownership of the directory, then opens or initializes its database.
    static FileDlrStorage open(Path directory, Duration claimDuration, Clock clock) {
        Objects.requireNonNull(clock, "clock");
        if (claimDuration == null || claimDuration.isNegative() || claimDuration.isZero()) {
            throw new IllegalArgumentException("Delivery claim duration must be positive");
        }
        Path root = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
        FileChannel channel = null;
        FileDlrStorage storage = null;
        try {
            Files.createDirectories(root);
            Path lockPath = root.resolve(LOCK_FILE);
            if (Files.isSymbolicLink(lockPath) || Files.isSymbolicLink(root.resolve(DATABASE_FILE))) {
                throw new DlrStorageException("fileDlrOpen|error|reason:symbolicLink");
            }
            channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException conflict) {
                lock = null;
            }
            if (lock == null) {
                throw new DlrStorageException("fileDlrOpen|error|reason:ownershipConflict");
            }
            storage = new FileDlrStorage(root, channel, lock, clock, claimDuration.toMillis());
            storage.openDatabase();
            return storage;
        } catch (IOException | SQLException | RuntimeException failure) {
            if (storage != null) {
                storage.close();
            } else if (channel != null) {
                try {
                    channel.close();
                } catch (IOException cleanup) {
                    failure.addSuppressed(cleanup);
                }
            }
            if (failure instanceof DlrStorageException storageFailure) {
                throw storageFailure;
            }
            throw new DlrStorageException("fileDlrOpen|error|reason:" + reason(failure), failure);
        }
    }

    private void openDatabase() throws IOException, SQLException {
        Path database = directory.resolve(DATABASE_FILE);
        boolean created = !Files.exists(database);
        if (!created && Files.size(database) == 0) {
            // SQLite would silently treat an empty file as a new database.
            throw new DlrStorageException("fileDlrOpen|error|reason:emptyDatabaseFile");
        }
        SQLiteConfig config = new SQLiteConfig();
        config.setJournalMode(SQLiteConfig.JournalMode.DELETE);
        config.setSynchronous(SQLiteConfig.SynchronousMode.FULL);
        config.setTransactionMode(SQLiteConfig.TransactionMode.IMMEDIATE);
        config.enforceForeignKeys(true);
        config.setBusyTimeout(0);
        config.setCacheSize(-8192); // about 8 MiB of page cache; not a total memory bound
        SQLiteDataSource source = new SQLiteDataSource(config);
        source.setUrl("jdbc:sqlite:" + database);
        connection = source.getConnection();
        requireSetting("journal_mode", "delete");
        requireSetting("synchronous", "2");
        if (created) {
            initializeSchema();
        } else {
            verifySchema();
        }
        // Prove the store is writable before declaring availability.
        write("startup probe", c -> {
            try (PreparedStatement probe = c.prepareStatement(
                    "INSERT INTO dlr_meta (meta_key, meta_value) VALUES ('startup_probe', ?) " +
                        "ON CONFLICT (meta_key) DO UPDATE SET meta_value = excluded.meta_value")) {
                probe.setString(1, Long.toString(System.currentTimeMillis()));
                probe.executeUpdate();
            }
            return null;
        });
    }

    private void requireSetting(String pragma, String expected) throws SQLException {
        String actual = single("PRAGMA " + pragma);
        if (!expected.equalsIgnoreCase(actual)) {
            throw new DlrStorageException("fileDlrOpen|error|reason:unsupportedSetting|pragma:" + pragma +
                "|value:" + MessageTrace.value(actual));
        }
    }

    private void initializeSchema() throws SQLException {
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            for (String ddl : SCHEMA) {
                statement.execute(ddl);
            }
            statement.execute("PRAGMA application_id = " + APPLICATION_ID);
            statement.execute("PRAGMA user_version = " + SCHEMA_VERSION);
            connection.commit();
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private void verifySchema() throws SQLException {
        if (!Integer.toString(APPLICATION_ID).equals(single("PRAGMA application_id"))) {
            throw new DlrStorageException("fileDlrOpen|error|reason:foreignDatabase");
        }
        String version = single("PRAGMA user_version");
        if (!Integer.toString(SCHEMA_VERSION).equals(version)) {
            throw new DlrStorageException("fileDlrOpen|error|reason:unsupportedSchemaVersion|version:" +
                MessageTrace.value(version));
        }
        int found = 0;
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT name FROM sqlite_schema")) {
            while (rows.next()) {
                if (REQUIRED_OBJECTS.contains(rows.getString(1))) {
                    found++;
                }
            }
        }
        if (found != REQUIRED_OBJECTS.size()) {
            throw new DlrStorageException("fileDlrOpen|error|reason:missingSchemaObject");
        }
    }

    synchronized void recordProviderAccepted(MessageState state, String providerName, String providerMessageId) {
        String gatewayId = prepareAccepted(state, providerName, providerMessageId);
        boolean accepted = write("record provider acceptance",
            c -> acceptIn(c, gatewayId, state, providerName, providerMessageId));
        if (!accepted) {
            throw new DlrStorageException("fileDlrAcceptance|error|gatewayMsgId:" + gatewayId +
                "|reason:terminalStateExists");
        }
    }

    synchronized Optional<MessageState> recordProviderRejected(MessageState state, String providerName,
                                                               String providerMessageId, int dlrState, String errorCode) {
        String gatewayId = prepareRejected(state, providerName, providerMessageId, dlrState, errorCode);
        return write("record provider rejection", c -> rejectIn(c, gatewayId, state));
    }

    synchronized Optional<MessageState> resolveDlr(String providerName, String providerMessageId,
                                                   MessageState.MessageStatus status, int dlrState, String errorCode) {
        Objects.requireNonNull(status, "status");
        requireCorrelation(providerName, providerMessageId);
        return write("resolve DLR state",
            c -> resolveIn(c, providerName, providerMessageId, status, dlrState, errorCode));
    }

    // Applies events in order as one transaction: either every result is published or none is.
    synchronized List<ProviderDlrEvent.Result> applyProviderBatch(List<ProviderDlrEvent> events) {
        List<ProviderDlrEvent> batch = List.copyOf(events);
        if (batch.isEmpty()) {
            return List.of();
        }
        if (batch.size() > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("Provider batch exceeds maximum size");
        }
        List<String> gatewayIds = new ArrayList<>(batch.size());
        for (ProviderDlrEvent event : batch) {
            gatewayIds.add(switch (event) {
                case ProviderDlrEvent.Accepted accepted ->
                    prepareAccepted(accepted.message(), accepted.providerName(), accepted.providerMessageId());
                case ProviderDlrEvent.Rejected rejected -> prepareRejected(rejected.message(), rejected.providerName(),
                    rejected.providerMessageId(), rejected.dlrState(), rejected.errorCode());
                case ProviderDlrEvent.Receipt ignored -> null;
            });
        }
        return write("persist provider batch", c -> {
            List<ProviderDlrEvent.Result> results = new ArrayList<>(batch.size());
            for (int i = 0; i < batch.size(); i++) {
                results.add(switch (batch.get(i)) {
                    case ProviderDlrEvent.Accepted a -> acceptIn(c, gatewayIds.get(i), a.message(), a.providerName(),
                        a.providerMessageId()) ? ProviderDlrEvent.Result.success(Optional.empty()) :
                        ProviderDlrEvent.Result.rejectedAcceptance();
                    case ProviderDlrEvent.Rejected r -> ProviderDlrEvent.Result.success(rejectIn(c, gatewayIds.get(i), r.message()));
                    case ProviderDlrEvent.Receipt r -> ProviderDlrEvent.Result.success(resolveIn(c, r.providerName(),
                        r.providerMessageId(), r.status(), r.dlrState(), r.errorCode()));
                });
            }
            return List.copyOf(results);
        });
    }

    private boolean acceptIn(Connection c, String gatewayId, MessageState state, String providerName,
                             String providerMessageId) throws SQLException {
        if (!persistAccepted(c, gatewayId, state, providerName, providerMessageId)) {
            return false;
        }
        try (PreparedStatement correlation = c.prepareStatement("""
                INSERT INTO provider_correlation (provider_name, provider_message_id, gateway_message_id, created_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (provider_name, provider_message_id) DO UPDATE SET
                    gateway_message_id = excluded.gateway_message_id, created_at = excluded.created_at
                """)) {
            correlation.setString(1, providerName);
            correlation.setString(2, providerMessageId);
            correlation.setString(3, gatewayId);
            correlation.setLong(4, state.getTimestamp());
            correlation.executeUpdate();
        }
        // A reused provider ID transfers ownership; the previous waiting owner no longer claims it.
        try (PreparedStatement previous = c.prepareStatement(CLEAR_PREVIOUS_OWNER_SQL)) {
            previous.setLong(1, state.getTimestamp());
            previous.setString(2, providerName);
            previous.setString(3, providerMessageId);
            previous.setString(4, gatewayId);
            previous.executeUpdate();
        }
        return true;
    }

    private Optional<MessageState> rejectIn(Connection c, String gatewayId, MessageState state) throws SQLException {
        String existing = deliveryStatus(c, gatewayId);
        if (existing != null && !MessageState.DeliveryStatus.WAITING_PROVIDER.name().equals(existing)) {
            return Optional.empty();
        }
        String sql = existing == null ? """
                INSERT INTO dlr_message (gateway_message_id, account_id, system_id, source_address, destination_address,
                    provider_name, provider_message_id, forward_dlr_url, http_callback_format, dlr_mask, reassembled_parts,
                    provider_status, dlr_state, error_code, delivery_channel, delivery_status, delivery_attempt_count,
                    last_attempt_at, next_attempt_at, last_delivery_result, resolved_at, updated_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """ : """
                UPDATE dlr_message SET gateway_message_id = ?, account_id = ?, system_id = ?, source_address = ?,
                    destination_address = ?, provider_name = ?, provider_message_id = ?, forward_dlr_url = ?,
                    http_callback_format = ?, dlr_mask = ?, reassembled_parts = ?, provider_status = ?, dlr_state = ?,
                    error_code = ?, delivery_channel = ?, delivery_status = ?, delivery_attempt_count = ?,
                    last_attempt_at = ?, next_attempt_at = ?, last_delivery_result = ?, resolved_at = ?, updated_at = ?
                WHERE gateway_message_id = ? AND delivery_status = 'WAITING_PROVIDER'
                """;
        try (PreparedStatement statement = c.prepareStatement(sql)) {
            bindAccepted(statement, gatewayId, state, state.getProviderName(), state.getProviderMessageId());
            setNullableLong(statement, 13, state.getDlrState() == null ? null : state.getDlrState().longValue());
            statement.setString(14, state.getErrorCode());
            statement.setString(15, state.getDeliveryChannel().name());
            statement.setString(16, state.getDeliveryStatus().name());
            statement.setInt(17, state.getDeliveryAttemptCount());
            setNullableLong(statement, 18, state.getLastAttemptAt());
            setNullableLong(statement, 19, state.getNextAttemptAt());
            statement.setString(20, state.getLastDeliveryResult());
            setNullableLong(statement, 21, state.getResolvedAt());
            statement.setLong(22, state.getTimestamp());
            if (existing == null) {
                statement.setLong(23, state.getTimestamp());
            } else {
                statement.setString(23, gatewayId);
            }
            statement.executeUpdate();
        }
        deleteCorrelations(c, gatewayId);
        return stateIn(c, gatewayId);
    }

    private Optional<MessageState> resolveIn(Connection c, String providerName, String providerMessageId,
                                             MessageState.MessageStatus status, int dlrState, String errorCode)
            throws SQLException {
        String gatewayId;
        try (PreparedStatement owner = c.prepareStatement(CORRELATION_OWNER_SQL)) {
            owner.setString(1, providerName);
            owner.setString(2, providerMessageId);
            try (ResultSet rows = owner.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
                gatewayId = rows.getString(1);
            }
        }
        long now = clock.millis();
        try (PreparedStatement resolve = c.prepareStatement("""
                UPDATE dlr_message SET provider_name = ?, provider_message_id = ?, provider_status = ?, dlr_state = ?,
                    error_code = ?, delivery_status = 'PENDING',
                    next_attempt_at = CASE WHEN delivery_channel = 'HTTP' THEN ? ELSE NULL END,
                    resolved_at = ?, updated_at = ?
                WHERE gateway_message_id = ? AND delivery_status = 'WAITING_PROVIDER'
                """)) {
            resolve.setString(1, providerName);
            resolve.setString(2, providerMessageId);
            resolve.setString(3, status.name());
            resolve.setInt(4, dlrState);
            resolve.setString(5, errorCode);
            resolve.setLong(6, now);
            resolve.setLong(7, now);
            resolve.setLong(8, now);
            resolve.setString(9, gatewayId);
            if (resolve.executeUpdate() == 0) {
                return Optional.empty();
            }
        }
        // First terminal outcome wins: every part correlation of the message is consumed together.
        deleteCorrelations(c, gatewayId);
        return stateIn(c, gatewayId);
    }

    private static void deleteCorrelations(Connection c, String gatewayId) throws SQLException {
        try (PreparedStatement delete = c.prepareStatement(
                "DELETE FROM provider_correlation WHERE gateway_message_id = ?")) {
            delete.setString(1, gatewayId);
            delete.executeUpdate();
        }
    }

    private static String deliveryStatus(Connection c, String gatewayId) throws SQLException {
        try (PreparedStatement select = c.prepareStatement(
                "SELECT delivery_status FROM dlr_message WHERE gateway_message_id = ?")) {
            select.setString(1, gatewayId);
            try (ResultSet rows = select.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        }
    }

    private static Optional<MessageState> stateIn(Connection c, String gatewayId) throws SQLException {
        try (PreparedStatement statement = c.prepareStatement(
                "SELECT " + STATE_COLUMNS + " FROM dlr_message WHERE gateway_message_id = ?")) {
            statement.setString(1, gatewayId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(readState(rows)) : Optional.empty();
            }
        }
    }

    private static void setNullableLong(PreparedStatement statement, int index, Long value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.INTEGER);
        } else {
            statement.setLong(index, value);
        }
    }

    synchronized Optional<MessageState> getState(String gatewayMsgId) {
        String gatewayId = parseGatewayId(gatewayMsgId);
        return read("read DLR state", c -> stateIn(c, gatewayId));
    }

    /** Current gateway owner of a provider correlation. */
    synchronized Optional<String> correlationOwner(String providerName, String providerMessageId) {
        return read("read provider correlation", c -> {
            try (PreparedStatement statement = c.prepareStatement(CORRELATION_OWNER_SQL)) {
                statement.setString(1, providerName);
                statement.setString(2, providerMessageId);
                try (ResultSet rows = statement.executeQuery()) {
                    return rows.next() ? Optional.of(rows.getString(1)) : Optional.empty();
                }
            }
        });
    }

    // Existing contract: returns the complete ordered result for one system; it is not paginated.
    synchronized List<MessageState> listPendingSmppDeliveries(String systemId) {
        if (systemId == null || systemId.isBlank()) {
            return List.of();
        }
        return read("list pending SMPP deliveries", c -> {
            List<String> ids = new ArrayList<>();
            try (PreparedStatement statement = c.prepareStatement(LIST_PENDING_SMPP_SQL)) {
                statement.setString(1, systemId);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        ids.add(rows.getString(1));
                    }
                }
            }
            return states(c, ids);
        });
    }

    // Each returned receipt's attempt and lease are committed before it is returned.
    synchronized List<MessageState> claimDueHttpDeliveries(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("Delivery limit must be positive");
        }
        int bounded = Math.min(limit, MAX_BATCH_SIZE);
        return write("claim due HTTP deliveries", c -> {
            long now = clock.millis();
            List<String> ids = new ArrayList<>(bounded);
            try (PreparedStatement due = c.prepareStatement(DUE_HTTP_SQL)) {
                due.setLong(1, now);
                due.setLong(2, now);
                due.setInt(3, bounded);
                try (ResultSet rows = due.executeQuery()) {
                    while (rows.next()) {
                        ids.add(rows.getString(1));
                    }
                }
            }
            for (String id : ids) {
                startAttemptIn(c, id, MessageState.DeliveryChannel.HTTP, now);
            }
            return states(c, ids);
        });
    }

    synchronized Optional<MessageState> startDeliveryAttempt(String gatewayMsgId,
                                                             MessageState.DeliveryChannel expectedChannel) {
        Objects.requireNonNull(expectedChannel, "expectedChannel");
        if (expectedChannel == MessageState.DeliveryChannel.NONE) {
            throw new IllegalArgumentException("A delivery attempt requires HTTP or SMPP channel");
        }
        String gatewayId = parseGatewayId(gatewayMsgId);
        return write("start DLR delivery attempt", c -> startAttemptIn(c, gatewayId, expectedChannel, clock.millis()) ?
            stateIn(c, gatewayId) : Optional.empty());
    }

    // Successful completion of the current attempt removes the record and its correlations (cascade).
    synchronized boolean completeDelivery(String gatewayMsgId, int expectedAttempt) {
        return finishAttempt("complete DLR delivery", gatewayMsgId, expectedAttempt,
            "DELETE FROM dlr_message WHERE gateway_message_id = ? AND delivery_status = 'PENDING' " +
                "AND delivery_attempt_count = ?", null, null);
    }

    synchronized boolean retryDelivery(String gatewayMsgId, int expectedAttempt, String result, long nextAttemptAt) {
        return finishAttempt("retry DLR delivery", gatewayMsgId, expectedAttempt, """
                UPDATE dlr_message SET last_delivery_result = ?, next_attempt_at = ?, claimed_until = NULL,
                    updated_at = ?
                WHERE gateway_message_id = ? AND delivery_status = 'PENDING' AND delivery_attempt_count = ?
                """, normalizeResult(result), nextAttemptAt);
    }

    synchronized boolean failDelivery(String gatewayMsgId, int expectedAttempt, String result) {
        return finishAttempt("fail DLR delivery", gatewayMsgId, expectedAttempt, """
                UPDATE dlr_message SET delivery_status = 'FAILED', last_delivery_result = ?, next_attempt_at = NULL,
                    claimed_until = NULL, failed_at = ?, updated_at = ?
                WHERE gateway_message_id = ? AND delivery_status = 'PENDING' AND delivery_attempt_count = ?
                """, normalizeResult(result), clock.millis());
    }

    synchronized boolean failInvalidDelivery(String gatewayMsgId, int expectedAttempt, String result) {
        return finishAttempt("mark invalid DLR delivery failed", gatewayMsgId, expectedAttempt, """
                UPDATE dlr_message SET delivery_status = 'FAILED', last_delivery_result = ?, next_attempt_at = NULL,
                    claimed_until = NULL, failed_at = ?, resolved_at = COALESCE(resolved_at, failed_at), updated_at = ?
                WHERE gateway_message_id = ? AND delivery_status = 'PENDING' AND delivery_attempt_count = ?
                """, normalizeResult(result), clock.millis());
    }

    // Applies an outcome only when the expected attempt is still the current one (stale-attempt fence).
    private boolean finishAttempt(String operation, String gatewayMsgId, int expectedAttempt, String sql,
                                  String result, Long timestamp) {
        if (expectedAttempt < 1) {
            throw new IllegalArgumentException("Expected attempt must be positive");
        }
        String gatewayId = parseGatewayId(gatewayMsgId);
        return write(operation, c -> {
            try (PreparedStatement statement = c.prepareStatement(sql)) {
                int index = 1;
                if (timestamp != null) {
                    statement.setString(index++, result);
                    statement.setLong(index++, timestamp);
                    statement.setLong(index++, clock.millis());
                }
                statement.setString(index++, gatewayId);
                statement.setInt(index, expectedAttempt);
                return statement.executeUpdate() == 1;
            }
        });
    }

    private boolean startAttemptIn(Connection c, String gatewayId, MessageState.DeliveryChannel channel, long now)
            throws SQLException {
        try (PreparedStatement start = c.prepareStatement(START_ATTEMPT_SQL)) {
            start.setLong(1, now);
            start.setLong(2, now + claimDurationMillis);
            start.setLong(3, now);
            start.setString(4, gatewayId);
            start.setString(5, channel.name());
            start.setLong(6, now);
            return start.executeUpdate() == 1;
        }
    }

    private static List<MessageState> states(Connection c, List<String> ids) throws SQLException {
        List<MessageState> states = new ArrayList<>(ids.size());
        for (String id : ids) {
            stateIn(c, id).ifPresent(states::add);
        }
        return List.copyOf(states);
    }

    private static String normalizeResult(String result) {
        return result == null || result.isBlank() ? null : result.trim();
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        DlrStorageException failure = null;
        try {
            if (connection != null) {
                connection.close();
            }
        } catch (SQLException e) {
            failure = new DlrStorageException("fileDlrClose|error|reason:" + reason(e), e);
        }
        try {
            if (ownerLock.isValid()) {
                ownerLock.release();
            }
            ownerChannel.close();
        } catch (IOException e) {
            if (failure == null) {
                failure = new DlrStorageException("fileDlrClose|error|reason:ownerRelease", e);
            } else {
                failure.addSuppressed(e);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private boolean persistAccepted(Connection c, String gatewayId, MessageState state,
                                    String providerName, String providerMessageId) throws SQLException {
        String existing = deliveryStatus(c, gatewayId);
        if (existing != null && !MessageState.DeliveryStatus.WAITING_PROVIDER.name().equals(existing)) {
            return false;
        }
        if (existing == null) {
            try (PreparedStatement insert = c.prepareStatement("""
                    INSERT INTO dlr_message (gateway_message_id, account_id, system_id, source_address,
                        destination_address, provider_name, provider_message_id, forward_dlr_url, http_callback_format,
                        dlr_mask, reassembled_parts, provider_status, delivery_channel, delivery_status,
                        delivery_attempt_count, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)
                    """)) {
                bindAccepted(insert, gatewayId, state, providerName, providerMessageId);
                insert.setString(13, state.getDeliveryChannel().name());
                insert.setString(14, state.getDeliveryStatus().name());
                insert.setLong(15, state.getTimestamp());
                insert.setLong(16, state.getTimestamp());
                insert.executeUpdate();
            }
            return true;
        }
        try (PreparedStatement update = c.prepareStatement("""
                UPDATE dlr_message SET gateway_message_id = ?, account_id = ?, system_id = ?, source_address = ?,
                    destination_address = ?, provider_name = ?, provider_message_id = ?, forward_dlr_url = ?,
                    http_callback_format = ?, dlr_mask = ?, reassembled_parts = ?, provider_status = ?, updated_at = ?
                WHERE gateway_message_id = ? AND delivery_status = 'WAITING_PROVIDER'
                """)) {
            bindAccepted(update, gatewayId, state, providerName, providerMessageId);
            update.setLong(13, state.getTimestamp());
            update.setString(14, gatewayId);
            return update.executeUpdate() == 1;
        }
    }

    private static void bindAccepted(PreparedStatement statement, String gatewayId, MessageState state,
                                     String providerName, String providerMessageId) throws SQLException {
        statement.setString(1, gatewayId);
        statement.setString(2, state.getAccountId());
        statement.setString(3, state.getSystemId());
        statement.setString(4, state.getSourceAddr());
        statement.setString(5, state.getDestAddr());
        statement.setString(6, providerName);
        statement.setString(7, providerMessageId);
        statement.setString(8, state.getForwardDlrUrl());
        statement.setString(9, state.getHttpCallbackFormat().name());
        statement.setInt(10, state.getDlrMask());
        String parts = encodeParts(state.getReassembledParts());
        if (parts == null) {
            statement.setNull(11, Types.VARCHAR);
        } else {
            statement.setString(11, parts);
        }
        statement.setString(12, state.getStatus().name());
    }

    private static MessageState readState(ResultSet rows) throws SQLException {
        MessageState state = new MessageState(rows.getString("gateway_message_id"), rows.getString("account_id"),
                rows.getString("system_id"), rows.getString("source_address"), rows.getString("destination_address"),
                rows.getString("forward_dlr_url"));
        state.setProviderName(rows.getString("provider_name"));
        state.setProviderMessageId(rows.getString("provider_message_id"));
        state.setHttpCallbackFormat(HttpCallbackFormat.valueOf(rows.getString("http_callback_format")));
        state.setDlrMask(rows.getInt("dlr_mask"));
        state.setReassembledParts(decodeParts(rows.getString("reassembled_parts")));
        state.setStatus(MessageState.MessageStatus.valueOf(rows.getString("provider_status")));
        Long dlrState = nullableLong(rows, "dlr_state");
        state.setDlrState(dlrState == null ? null : dlrState.intValue());
        state.setErrorCode(rows.getString("error_code"));
        state.setDeliveryChannel(MessageState.DeliveryChannel.valueOf(rows.getString("delivery_channel")));
        state.setDeliveryStatus(MessageState.DeliveryStatus.valueOf(rows.getString("delivery_status")));
        state.setDeliveryAttemptCount(rows.getInt("delivery_attempt_count"));
        state.setLastAttemptAt(nullableLong(rows, "last_attempt_at"));
        state.setNextAttemptAt(nullableLong(rows, "next_attempt_at"));
        state.setLastDeliveryResult(rows.getString("last_delivery_result"));
        state.setResolvedAt(nullableLong(rows, "resolved_at"));
        state.setTimestamp(rows.getLong("updated_at"));
        return state;
    }

    private static Long nullableLong(ResultSet rows, String column) throws SQLException {
        long value = rows.getLong(column);
        return rows.wasNull() ? null : value;
    }

    private static String encodeParts(List<String> parts) {
        if (parts == null) {
            return null;
        }
        try {
            return JSON.writeValueAsString(parts);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("fileDlrEncoding|error|reason:reassembledParts", e);
        }
    }

    private static List<String> decodeParts(String encoded) throws SQLException {
        if (encoded == null) {
            return null;
        }
        try {
            return JSON.readValue(encoded, new TypeReference<List<String>>() { });
        } catch (JsonProcessingException e) {
            throw new SQLException("fileDlrDecoding|error|reason:reassembledParts", e);
        }
    }

    private String prepareRejected(MessageState state, String providerName, String providerMessageId,
                                   int dlrState, String errorCode) {
        Objects.requireNonNull(state, "state");
        if (providerMessageId != null && !providerMessageId.isBlank()) {
            requireCorrelation(providerName, providerMessageId);
            state.setProviderName(providerName);
            state.setProviderMessageId(providerMessageId);
        } else {
            state.setProviderName(null);
            state.setProviderMessageId(null);
        }
        final String gatewayId = parseGatewayId(state.getGatewayMsgId());
        long now = clock.millis();
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
        validateDeliveryTarget(state);
        return gatewayId;
    }

    private static void requireCorrelation(String providerName, String providerMessageId) {
        if (providerName == null || providerName.isBlank() || providerMessageId == null || providerMessageId.isBlank()) {
            throw new IllegalArgumentException("Provider name and provider message ID must not be blank");
        }
    }

    private String prepareAccepted(MessageState state, String providerName, String providerMessageId) {
        Objects.requireNonNull(state, "state");
        requireCorrelation(providerName, providerMessageId);
        final String gatewayId = parseGatewayId(state.getGatewayMsgId());
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
        state.setTimestamp(clock.millis());
        validateDeliveryTarget(state);
        return gatewayId;
    }

    private static void validateDeliveryTarget(MessageState state) {
        if (state.getDeliveryChannel() == null || state.getDeliveryChannel() == MessageState.DeliveryChannel.NONE) {
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

    private static String parseGatewayId(String gatewayMsgId) {
        try {
            return UUID.fromString(Objects.requireNonNull(gatewayMsgId)).toString();
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new DlrStorageException("Invalid gateway message ID", e);
        }
    }

    private <T> T read(String operation, SqlWork<T> work) {
        requireAvailable();
        try {
            return work.apply(connection);
        } catch (SQLException e) {
            throw latch(operation, e);
        }
    }

    /**
     * One durable transaction. A constraint failure with a verified rollback leaves the store usable;
     * any other failure (commit, I/O, disk full, failed rollback) makes it unavailable until reopened.
     */
    private <T> T write(String operation, SqlWork<T> work) {
        requireAvailable();
        try {
            connection.setAutoCommit(false);
        } catch (SQLException e) {
            throw latch(operation, e);
        }
        try {
            T result = work.apply(connection);
            if (Boolean.FALSE.equals(result)) {
                restore(operation, null);
                return result;
            }
            beforeCommit.run(connection);
            connection.commit();
            connection.setAutoCommit(true);
            return result;
        } catch (SQLException e) {
            if ((e.getErrorCode() & 0xff) == SQLITE_CONSTRAINT) {
                restore(operation, e);
                throw new DlrStorageException("fileDlrWrite|error|operation:" + operation + "|reason:constraint", e);
            }
            throw latch(operation, e);
        } catch (RuntimeException e) {
            restore(operation, e);
            throw e;
        }
    }

    private void restore(String operation, Exception cause) {
        try {
            connection.rollback();
            connection.setAutoCommit(true);
        } catch (SQLException rollbackFailure) {
            if (cause != null) {
                rollbackFailure.addSuppressed(cause);
            }
            throw latch(operation, rollbackFailure);
        }
    }

    private DlrStorageException latch(String operation, SQLException cause) {
        unavailable = new DlrStorageException("fileDlrStorage|error|operation:" + operation + "|reason:" +
            reason(cause), cause);
        try {
            connection.close();
        } catch (SQLException closeFailure) {
            unavailable.addSuppressed(closeFailure);
        }
        return unavailable;
    }

    private void requireAvailable() {
        if (closed) {
            throw new DlrStorageException("fileDlrStorage|error|reason:closed");
        }
        if (unavailable != null) {
            throw new DlrStorageException("fileDlrStorage|error|reason:unavailable", unavailable);
        }
    }

    private String single(String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            return rows.next() ? rows.getString(1) : null;
        }
    }

    private static String reason(Throwable failure) {
        if (failure instanceof SQLException sql) {
            return "sqliteCode" + (sql.getErrorCode() & 0xff);
        }
        return MessageTrace.value(failure.getClass().getSimpleName());
    }

    @FunctionalInterface
    interface SqlHook {
        void run(Connection connection) throws SQLException;
    }

    @FunctionalInterface
    private interface SqlWork<T> {
        T apply(Connection connection) throws SQLException;
    }
}
