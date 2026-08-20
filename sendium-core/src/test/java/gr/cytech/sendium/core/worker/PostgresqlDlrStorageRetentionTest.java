package gr.cytech.sendium.core.worker;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Retention cleanup piggybacks on storage activity, so a failing pass must stay isolated from the operation that
 * triggered it: it may not reject a submission, and it may not be retried by every following call.
 */
class PostgresqlDlrStorageRetentionTest {
    private static final long ONE_MINUTE_MILLIS = 60_000L;

    private final AtomicInteger retentionAttempts = new AtomicInteger();

    private DataSource dataSource;

    @BeforeEach
    void setUp() throws SQLException {
        Connection connection = mock(Connection.class, RETURNS_DEEP_STUBS);
        dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);
        doNothing().when(connection).setAutoCommit(false);

        ResultSet noRows = mock(ResultSet.class);
        when(noRows.next()).thenReturn(false);
        PreparedStatement read = mock(PreparedStatement.class);
        when(read.executeQuery()).thenReturn(noRows);
        doNothing().when(read).setObject(anyInt(), org.mockito.ArgumentMatchers.any());

        when(connection.prepareStatement(anyString())).thenAnswer(invocation -> {
            // Only the retention statements filter on an age threshold.
            if (invocation.<String>getArgument(0).contains("created_at <")) {
                retentionAttempts.incrementAndGet();
                throw new SQLException("permission denied for table", "42501");
            }
            return read;
        });
    }

    @Test
    void failedRetentionDoesNotFailTheTriggeringOperation() {
        PostgresqlDlrStorage storage = new PostgresqlDlrStorage(dataSource, 1, 0, ONE_MINUTE_MILLIS);

        assertThat(storage.getState(UUID.randomUUID().toString())).isEmpty();
        assertThat(retentionAttempts).hasValue(1);
    }

    @Test
    void failedRetentionIsNotRetriedUntilTheNextInterval() {
        PostgresqlDlrStorage storage = new PostgresqlDlrStorage(dataSource, 1, 0, ONE_MINUTE_MILLIS);

        for (int call = 0; call < 5; call++) {
            assertThat(storage.getState(UUID.randomUUID().toString())).isEmpty();
        }

        assertThat(retentionAttempts).hasValue(1);
    }
}
