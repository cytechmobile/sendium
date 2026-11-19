package gr.cytech.sendium.core.worker;

import gr.cytech.sendium.core.message.DlrReturnMetadata.HttpCallbackFormat;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

final class FileDlrTestSupport {
    private FileDlrTestSupport() {
    }

    static MessageState httpState() {
        MessageState state = new MessageState(UUID.randomUUID().toString(), "account", "system",
                "from", "to", "https://callback.test/dlr");
        state.setDeliveryChannel(MessageState.DeliveryChannel.HTTP);
        state.setHttpCallbackFormat(HttpCallbackFormat.JSON_POST);
        state.setDlrMask(1);
        state.setReassembledParts(List.of(UUID.randomUUID().toString(), UUID.randomUUID().toString()));
        return state;
    }

    static Connection raw(Path directory) throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + directory.resolve(FileDlrStorage.DATABASE_FILE));
    }

    static String single(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            return rows.next() ? rows.getString(1) : null;
        }
    }

    static void execute(Path directory, String sql) throws SQLException {
        try (Connection connection = raw(directory); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    /**
     * Inserts resolved receipts in one transaction while the store is closed. The timestamp is the due time of
     * pending HTTP rows and the failure instant of failed rows.
     */
    static void seed(Path directory, int count, String channel, String status, String systemId, long timestamp)
            throws SQLException {
        try (Connection connection = raw(directory)) {
            connection.setAutoCommit(false);
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO dlr_message (gateway_message_id, system_id, forward_dlr_url, http_callback_format,
                        dlr_mask, provider_status, delivery_channel, delivery_status, next_attempt_at, resolved_at,
                        failed_at, created_at, updated_at)
                    VALUES (?, ?, 'https://seed.test', 'KANNEL_GET', 3, 'DELIVERED', ?, ?, ?, ?, ?, 0, 0)
                    """)) {
                boolean failed = "FAILED".equals(status);
                for (int i = 0; i < count; i++) {
                    insert.setString(1, UUID.randomUUID().toString());
                    insert.setString(2, systemId);
                    insert.setString(3, channel);
                    insert.setString(4, status);
                    insert.setObject(5, !failed && "HTTP".equals(channel) ? timestamp : null);
                    insert.setLong(6, timestamp);
                    insert.setObject(7, failed ? timestamp : null);
                    insert.addBatch();
                }
                insert.executeBatch();
            }
            connection.commit();
        }
    }

    static MessageState smppState(String systemId) {
        MessageState state = new MessageState(UUID.randomUUID().toString(), "account", systemId, "from", "to", null);
        state.setDeliveryChannel(MessageState.DeliveryChannel.SMPP);
        return state;
    }

    /** Accepts and terminally resolves a receipt at the clock's current time, leaving it pending delivery. */
    static MessageState pending(FileDlrStorage storage, MessageState state) {
        String providerId = UUID.randomUUID().toString();
        storage.recordProviderAccepted(state, "P", providerId);
        storage.resolveDlr("P", providerId, MessageState.MessageStatus.DELIVERED, 1, "0").orElseThrow();
        return state;
    }

    static final class MutableClock extends Clock {
        private final AtomicLong millis = new AtomicLong();

        void set(long value) {
            millis.set(value);
        }

        @Override
        public long millis() {
            return millis.get();
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis());
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
