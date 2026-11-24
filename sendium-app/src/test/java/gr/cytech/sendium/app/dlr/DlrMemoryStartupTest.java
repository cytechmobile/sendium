package gr.cytech.sendium.app.dlr;

import gr.cytech.sendium.core.message.DlrReturnMetadata;
import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.outbound.OutboundDlrPolicy;
import gr.cytech.sendium.core.outbound.OutboundWork.SourceId;
import gr.cytech.sendium.core.worker.ManagedDlrStorage;
import gr.cytech.sendium.core.worker.MessageState;
import io.agroal.api.AgroalDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.agroal.DataSource;
import io.quarkus.arc.InjectableInstance;
import io.quarkus.flyway.FlywayDataSource;
import io.quarkus.test.QuarkusExtensionTest;
import io.smallrye.health.SmallRyeHealthReporter;
import jakarta.inject.Inject;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DlrMemoryStartupTest {
    @RegisterExtension
    static final QuarkusExtensionTest APP = DlrStartupSupport.application()
            .overrideConfigKey("sendium.dlr.persistence.enabled", "true")
            .overrideConfigKey("sendium.dlr.storage.backend", "memory")
            .overrideConfigKey("sendium.dlr.storage.memory.max-tracked-submissions", "1");

    @Inject ManagedDlrStorage storage;
    @Inject MeterRegistry metrics;
    @Inject SmallRyeHealthReporter health;
    @Inject @DataSource("dlr") InjectableInstance<AgroalDataSource> datasource;
    @Inject @FlywayDataSource("dlr") InjectableInstance<Flyway> flyway;

    @Test
    void memoryStartsWithoutDurableDlrResources() throws Exception {
        if (!datasource.isUnsatisfied()) {
            assertThat(datasource.getHandle().getBean().isActive()).isFalse();
        }
        if (!flyway.isUnsatisfied()) {
            assertThat(flyway.getHandle().getBean().isActive()).isFalse();
        }
        var source = new SourceId(UUID.randomUUID());
        var message = new StandardMessage();
        message.serial = source.value().toString();
        message.dlrReturnMetadata = DlrReturnMetadata.http("account", "sender", "recipient", "https://callback.test/dlr");
        var policy = (OutboundDlrPolicy) ManagedDlrStorage.class.getMethod("outboundDlrPolicy").invoke(storage);
        policy.reserveAdmission(source, message);
        policy.prepareProvider(message.serial, Set.of(source));
        var state = new MessageState(message.serial, "account", "system", "sender", "recipient", "https://callback.test/dlr");
        state.setDeliveryChannel(MessageState.DeliveryChannel.HTTP);
        storage.recordProviderAccepted(state, "P", "id");
        assertThat(storage.getState(message.serial)).isPresent();
        assertThat(metrics.get("sendium.dlr.storage.capacity.used").gauge().value()).isEqualTo(1);
        assertThat(metrics.find("sendium.dlr.storage.messages").gauges()).isEmpty();
        assertThat(health.getReadiness().isDown()).isFalse();
        assertThat(health.getReadiness().getPayload().toString()).contains("\"backend\":\"memory\"");
    }
}
