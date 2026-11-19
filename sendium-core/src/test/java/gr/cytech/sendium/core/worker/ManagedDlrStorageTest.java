package gr.cytech.sendium.core.worker;

import io.agroal.api.AgroalDataSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkus.arc.InjectableInstance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Optional;

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

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = ';', value = {
        "missing path; file; ; P7D; false; reason:missingPath",
        "blank path; file; '  '; P7D; false; reason:missingPath",
        "zero retention; file; dlr; PT0S; false; reason:invalidFailedRetention",
        "negative retention; file; dlr; -PT1S; false; reason:invalidFailedRetention",
        "active postgresql override; file; dlr; P7D; true; reason:postgresqlDatasourceActive",
        "memory selector; memory; dlr; P7D; false; backend:memory|reason:unsupported",
        "unknown selector; sqlite; dlr; P7D; false; backend:sqlite|reason:unknownBackend",
        "unsanitized selector; 'x|y'; dlr; P7D; false; backend:x_y|reason:unknownBackend"})
    void rejectsUnsupportedSelectionsWithoutFallback(String name, String backend, String path, String retention,
                                                     boolean postgresqlActive, String reason,
                                                     @TempDir Path root) {
        when(postgresqlDataSource.getHandle().getBean().isActive()).thenReturn(postgresqlActive);
        storage.flywayActive = postgresqlActive;
        storage.flywayMigrateAtStart = postgresqlActive;
        storage.selectedBackend = backend;
        storage.filePath = Optional.ofNullable(path).map(value -> value.isBlank() ? value : root.resolve(value).toString());
        storage.fileFailedRetention = Duration.parse(retention);

        assertThatThrownBy(storage::initialize).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(reason);
        verify(postgresqlDataSource, never()).get();
        assertThat(root.resolve("dlr")).doesNotExist();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"same directory, sms, sms", "dlr inside sms, sms/dlr, sms", "sms inside dlr, dlr, dlr/sms"})
    void rejectsDlrPathOverlappingFilePendingSpool(String name, String dlr, String sms, @TempDir Path root)
            throws IOException {
        Path marker = Files.writeString(Files.createDirectories(root.resolve("sms")).resolve("marker"), "sms");
        storage.selectedBackend = "file";
        storage.filePath = Optional.of(root.resolve(dlr).toString());
        storage.pendingBackend = "file";
        storage.pendingPath = Optional.of(root.resolve(sms).toString());

        assertThatThrownBy(storage::initialize).isInstanceOf(IllegalStateException.class)
                .hasMessage("dlrStorage|error|backend:file|reason:overlapsPendingPath");
        assertThat(Files.readString(marker)).isEqualTo("sms");
        try (var files = Files.list(root.resolve("sms"))) {
            assertThat(files).containsExactly(marker);
        }
    }

    @Test
    void opensIndependentFileNamespaceBesideFilePendingSpool(@TempDir Path root) {
        storage.selectedBackend = "file";
        storage.filePath = Optional.of(root.resolve("dlr").toString());
        storage.pendingBackend = "file";
        storage.pendingPath = Optional.of(root.resolve("sms").toString());
        try {
            storage.initialize();
            assertThat(storage.backend()).isEqualTo("file");
            assertThat(root.resolve("dlr").resolve(FileDlrStorage.DATABASE_FILE)).isRegularFile();
            assertThat(root.resolve("sms")).doesNotExist();
            verify(postgresqlDataSource, never()).get();
        } finally {
            storage.close();
        }
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
