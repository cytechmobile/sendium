package gr.cytech.sendium.app.dlr;

import gr.cytech.sendium.core.worker.DlrService;
import gr.cytech.sendium.core.worker.DlrStorage;
import gr.cytech.sendium.core.worker.DlrStorageMetrics;
import gr.cytech.sendium.core.worker.DlrStorageReadinessCheck;
import gr.cytech.sendium.core.worker.ForwardDlrService;
import gr.cytech.sendium.core.worker.ProviderDlrProcessor;
import io.agroal.api.AgroalDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.agroal.DataSource;
import io.quarkus.arc.InjectableInstance;
import io.quarkus.flyway.FlywayDataSource;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.assertj.core.api.Assertions.assertThat;

class DlrDisabledStartupTest {
    @RegisterExtension
    static final QuarkusExtensionTest APP = DlrStartupSupport.application();

    @Inject Instance<DlrStorage> storage;
    @Inject Instance<DlrService> service;
    @Inject Instance<ProviderDlrProcessor> processor;
    @Inject Instance<ForwardDlrService> forwarder;
    @Inject Instance<DlrStorageReadinessCheck> readiness;
    @Inject Instance<DlrStorageMetrics> collector;
    @Inject MeterRegistry metrics;
    @Inject @DataSource("dlr") InjectableInstance<AgroalDataSource> datasource;
    @Inject @FlywayDataSource("dlr") InjectableInstance<Flyway> flyway;

    @Test
    void disabledBuildCreatesNoDlrSubsystem() {
        assertThat(storage.isUnsatisfied()).isTrue();
        assertThat(service.isUnsatisfied()).isTrue();
        assertThat(processor.isUnsatisfied()).isTrue();
        assertThat(forwarder.isUnsatisfied()).isTrue();
        assertThat(readiness.isUnsatisfied()).isTrue();
        assertThat(collector.isUnsatisfied()).isTrue();
        if (!datasource.isUnsatisfied()) {
            assertThat(datasource.getHandle().getBean().isActive()).isFalse();
        }
        if (!flyway.isUnsatisfied()) {
            assertThat(flyway.getHandle().getBean().isActive()).isFalse();
        }
        assertThat(metrics.find("sendium.dlr.storage.selected").gauge()).isNull();
        assertThat(metrics.find("sendium.dlr.storage.messages").gauges()).isEmpty();
    }
}
