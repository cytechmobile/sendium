package gr.cytech.sendium.core.worker;

import gr.cytech.sendium.core.worker.FileDlrTestSupport.MutableClock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static gr.cytech.sendium.core.worker.FileDlrTestSupport.httpState;
import static gr.cytech.sendium.core.worker.FileDlrTestSupport.pending;
import static gr.cytech.sendium.core.worker.FileDlrTestSupport.raw;
import static gr.cytech.sendium.core.worker.FileDlrTestSupport.smppState;
import static org.assertj.core.api.Assertions.assertThat;

class FileDlrIndexAccessTest {
    private static final int BACKLOG = 100_000;

    @TempDir
    Path root;

    @Test
    void queriesDoNotHydrateUnrelatedBacklog() throws Exception {
        var clock = new MutableClock();
        clock.set(1_000);
        FileDlrStorage.open(root, Duration.ofMinutes(5), clock).close();
        seedUnrelatedBacklog();

        try (var storage = FileDlrStorage.open(root, Duration.ofMinutes(5), clock)) {
            MessageState waiting = httpState();
            storage.recordProviderAccepted(waiting, "target", "X");
            MessageState smpp = pending(storage, smppState("target-system"));
            MessageState http = pending(storage, httpState());

            assertThat(storage.correlationOwner("target", "X")).contains(waiting.getGatewayMsgId());
            assertThat(storage.listPendingSmppDeliveries("target-system")).extracting(MessageState::getGatewayMsgId)
                    .containsExactly(smpp.getGatewayMsgId());
            assertThat(storage.claimDueHttpDeliveries(10)).extracting(MessageState::getGatewayMsgId)
                    .containsExactly(http.getGatewayMsgId());
        }

        try (Connection connection = raw(root)) {
            for (String sql : List.of(FileDlrStorage.CORRELATION_OWNER_SQL, FileDlrStorage.CLEAR_PREVIOUS_OWNER_SQL,
                    FileDlrStorage.LIST_PENDING_SMPP_SQL, FileDlrStorage.DUE_HTTP_SQL)) {
                String plan = plan(connection, sql);
                assertThat(plan).as(sql).containsAnyOf("USING INDEX", "USING COVERING INDEX", "USING PRIMARY KEY")
                        .doesNotContain("SCAN dlr_message", "SCAN provider_correlation", "TEMP B-TREE");
            }
        }
    }

    private void seedUnrelatedBacklog() throws Exception {
        try (Connection connection = raw(root)) {
            connection.setAutoCommit(false);
            try (PreparedStatement message = connection.prepareStatement("""
                    INSERT INTO dlr_message (gateway_message_id, system_id, provider_name, provider_message_id,
                        forward_dlr_url, http_callback_format, dlr_mask, provider_status, delivery_channel,
                        delivery_status, next_attempt_at, resolved_at, created_at, updated_at)
                    VALUES (?, ?, ?, ?, 'https://unrelated.test', 'KANNEL_GET', 3, ?, ?, ?, ?, ?, 1, 1)
                    """);
                 PreparedStatement correlation = connection.prepareStatement(
                         "INSERT INTO provider_correlation VALUES ('unrelated', ?, ?, 1)")) {
                for (int i = 0; i < BACKLOG; i++) {
                    String id = UUID.randomUUID().toString();
                    boolean waiting = i % 3 == 0;
                    boolean http = i % 3 == 1;
                    message.setString(1, id);
                    message.setString(2, "other-system-" + (i % 50));
                    message.setString(3, waiting ? "unrelated" : null);
                    message.setString(4, waiting ? "id-" + i : null);
                    message.setString(5, waiting ? "SENT" : "DELIVERED");
                    message.setString(6, http ? "HTTP" : "SMPP");
                    message.setString(7, waiting ? "WAITING_PROVIDER" : "PENDING");
                    // Pending HTTP backlog is scheduled in the future, so it is not due.
                    if (http) {
                        message.setLong(8, 1_000_000_000L + i);
                    } else {
                        message.setNull(8, java.sql.Types.INTEGER);
                    }
                    if (waiting) {
                        message.setNull(9, java.sql.Types.INTEGER);
                    } else {
                        message.setLong(9, 1);
                    }
                    message.addBatch();
                    if (waiting) {
                        correlation.setString(1, "id-" + i);
                        correlation.setString(2, id);
                        correlation.addBatch();
                    }
                }
                message.executeBatch();
                correlation.executeBatch();
            }
            connection.commit();
        }
    }

    private static String plan(Connection connection, String sql) throws Exception {
        List<String> details = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("EXPLAIN QUERY PLAN " + sql)) {
            for (int i = 1; i <= statement.getParameterMetaData().getParameterCount(); i++) {
                statement.setLong(i, 1);
            }
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    details.add(rows.getString(4));
                }
            }
        }
        return String.join(" | ", details);
    }
}
