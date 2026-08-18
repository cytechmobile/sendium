package gr.cytech.sendium.core.worker;

import io.agroal.api.AgroalDataSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkus.arc.InjectableInstance;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Answers.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConfiguredDlrStorageTest {
    private Instance<MvStoreDlrStorage> mvStoreInstance;
    private MvStoreDlrStorage mvStore;
    private InjectableInstance<AgroalDataSource> postgresqlDataSource;
    private SimpleMeterRegistry meterRegistry;
    private ConfiguredDlrStorage storage;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        mvStoreInstance = mock(Instance.class);
        mvStore = mock(MvStoreDlrStorage.class);
        postgresqlDataSource = mock(InjectableInstance.class, RETURNS_DEEP_STUBS);
        meterRegistry = new SimpleMeterRegistry();
        when(mvStoreInstance.get()).thenReturn(mvStore);
        when(postgresqlDataSource.getHandle().getBean().isActive()).thenReturn(false);

        storage = new ConfiguredDlrStorage();
        storage.configuredBackend = "mvstore";
        storage.mvStoreStorage = mvStoreInstance;
        storage.postgresqlDataSource = postgresqlDataSource;
        storage.meterRegistry = meterRegistry;
    }

    @Test
    void selectsMvStoreAndRecordsLowCardinalityMetrics() {
        when(mvStore.getState("sensitive-gateway-id")).thenReturn(Optional.empty());

        storage.initialize();
        storage.getState("sensitive-gateway-id");

        verify(mvStore).getState("sensitive-gateway-id");
        assertThat(storage.backend()).isEqualTo("mvstore");
        assertThat(meterRegistry.find("sendium.dlr.storage.selected")
                .tag("backend", "mvstore").gauge().value()).isEqualTo(1.0);
        assertThat(meterRegistry.find("sendium.dlr.storage.operation")
                .tags("backend", "mvstore", "operation", "get_state", "outcome", "success")
                .timer().count()).isOne();
        assertThat(meterRegistry.getMeters())
                .flatExtracting(meter -> meter.getId().getTags())
                .noneMatch(tag -> tag.getValue().contains("sensitive"));
    }

    @Test
    void recordsThrownStorageFailureAsError() {
        when(mvStore.markAsFailed("gateway-id")).thenThrow(new DlrStorageException("failure"));
        storage.initialize();

        assertThatThrownBy(() -> storage.markAsFailed("gateway-id"))
                .isInstanceOf(DlrStorageException.class);

        assertThat(meterRegistry.find("sendium.dlr.storage.operation")
                .tags("backend", "mvstore", "operation", "mark_failed", "outcome", "error")
                .timer().count()).isOne();
    }

    @Test
    void delegatesBatchSavesAndRecordsMetrics() {
        List<MessageState> states = List.of(
                new MessageState("gateway-id", "system", "source", "destination", null));
        storage.initialize();

        storage.saveInitialStates(states);

        verify(mvStore).saveInitialStates(states);
        assertThat(meterRegistry.find("sendium.dlr.storage.operation")
                .tags("backend", "mvstore", "operation", "save_initial_batch", "outcome", "success")
                .timer().count()).isOne();
    }

    @Test
    void rejectsUnknownBackend() {
        storage.configuredBackend = "unknown";

        assertThatThrownBy(storage::initialize)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Unsupported DLR storage backend: unknown");
    }

    @Test
    void rejectsActivePostgresqlDatasourceForMvStore() {
        when(postgresqlDataSource.getHandle().getBean().isActive()).thenReturn(true);

        assertThatThrownBy(storage::initialize)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be inactive");
    }

    @Test
    void rejectsInactivePostgresqlDatasourceWhenSelected() {
        storage.configuredBackend = "postgresql";

        assertThatThrownBy(storage::initialize)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires the active 'dlr' datasource and Flyway");
    }

    @Test
    void rejectsPostgresqlDatasourceWithoutFlyway() {
        when(postgresqlDataSource.getHandle().getBean().isActive()).thenReturn(true);
        storage.configuredBackend = "postgresql";

        assertThatThrownBy(storage::initialize)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires the active 'dlr' datasource and Flyway");
    }

    @Test
    void selectsActivePostgresqlDatasourceWithoutMvStoreFallback() {
        AgroalDataSource dataSource = mock(AgroalDataSource.class);
        when(postgresqlDataSource.getHandle().getBean().isActive()).thenReturn(true);
        when(postgresqlDataSource.get()).thenReturn(dataSource);
        storage.configuredBackend = " POSTGRESQL ";
        storage.flywayActive = true;
        storage.flywayMigrateAtStart = true;

        storage.initialize();

        assertThat(storage.backend()).isEqualTo("postgresql");
        assertThat(storage.mode()).isEqualTo("persistent");
        verify(postgresqlDataSource).get();
        verify(mvStoreInstance, org.mockito.Mockito.never()).get();
    }
}
