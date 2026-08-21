package gr.cytech.sendium.core.worker;

import io.agroal.api.AgroalDataSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkus.arc.InjectableInstance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Answers.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
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
}
