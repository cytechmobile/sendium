package gr.cytech.sendium.core.worker;

import gr.cytech.sendium.core.message.DlrReturnMetadata.HttpCallbackFormat;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
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
