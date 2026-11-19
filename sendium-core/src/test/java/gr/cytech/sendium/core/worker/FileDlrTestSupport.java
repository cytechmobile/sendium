package gr.cytech.sendium.core.worker;

import gr.cytech.sendium.core.message.DlrReturnMetadata.HttpCallbackFormat;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

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
}
