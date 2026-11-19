package gr.cytech.sendium.core.worker;

import io.agroal.api.AgroalDataSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkus.arc.InjectableInstance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;

import static org.mockito.ArgumentMatchers.anyString;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Answers.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ManagedDlrStorageTest {
    private InjectableInstance<AgroalDataSource> postgresqlDataSource;
    private ManagedDlrStorage storage;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        postgresqlDataSource = mock(InjectableInstance.class, RETURNS_DEEP_STUBS);
        when(postgresqlDataSource.getHandle().getBean().isActive()).thenReturn(false);

        storage = new ManagedDlrStorage();
        storage.postgresqlDataSource = postgresqlDataSource;
        storage.meterRegistry = new SimpleMeterRegistry();
        storage.deliveryClaimDuration = Duration.ofMinutes(5);
    }

    @Test
    void rejectsInactivePostgresqlDatasource() {
        assertThatThrownBy(storage::initialize)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires the active 'dlr' datasource and Flyway");
    }

    @Test
    void rejectsPostgresqlDatasourceWithoutFlyway() {
        when(postgresqlDataSource.getHandle().getBean().isActive()).thenReturn(true);

        assertThatThrownBy(storage::initialize)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires the active 'dlr' datasource and Flyway");
    }

    @Test
    void initializesActivePostgresqlDatasource() {
        AgroalDataSource dataSource = mock(AgroalDataSource.class);
        when(postgresqlDataSource.getHandle().getBean().isActive()).thenReturn(true);
        when(postgresqlDataSource.get()).thenReturn(dataSource);
        storage.flywayActive = true;
        storage.flywayMigrateAtStart = true;

        storage.initialize();

        assertThat(storage.backend()).isEqualTo("postgresql");
        verify(postgresqlDataSource).get();
    }

    @Test
    void probesPostgresqlSchemaWithoutRunningRetention() throws SQLException {
        AgroalDataSource dataSource = mock(AgroalDataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        initialize(dataSource);

        storage.verifyAvailable();

        verify(connection).prepareStatement("""
                SELECT 1
                FROM sendium_dlr.dlr_message
                WHERE FALSE
                """);
        verify(statement).executeQuery();
        verify(statement).close();
        verify(connection).close();
    }

    @Test
    void probeFailurePreservesSqlDiagnosticsWithoutLeakingThemIntoTheContract() throws SQLException {
        AgroalDataSource dataSource = mock(AgroalDataSource.class);
        SQLException failure = new SQLException("private connection detail", "42P01|\r\n", 7);
        when(dataSource.getConnection()).thenThrow(failure);
        initialize(dataSource);

        assertThatThrownBy(storage::verifyAvailable)
                .isInstanceOf(DlrStorageException.class)
                .hasCause(failure)
                .hasMessage("dlrStorageAvailability|error|backend:postgresql|reason:schemaProbeFailed|sqlState:42P01___|errorCode:7");
    }

    @Test
    void closingManagedStorageDoesNotCloseTheContainerDatasource() {
        AgroalDataSource dataSource = mock(AgroalDataSource.class);
        initialize(dataSource);

        storage.close();

        verify(dataSource, never()).close();
    }

    private void initialize(AgroalDataSource dataSource) {
        when(postgresqlDataSource.getHandle().getBean().isActive()).thenReturn(true);
        when(postgresqlDataSource.get()).thenReturn(dataSource);
        storage.flywayActive = true;
        storage.flywayMigrateAtStart = true;
        storage.initialize();
    }
}
