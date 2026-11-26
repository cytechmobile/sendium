package gr.cytech.sendium.app.dlr;

import gr.cytech.sendium.app.storage.MessageStorageProfile;
import gr.cytech.sendium.app.storage.StandaloneOutboundPipeline;
import gr.cytech.sendium.core.outbound.OutboundCoordinator;
import io.micrometer.core.instrument.MeterRegistry;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DlrExcludedMemoryStartupTest {
    @Test
    void explicitMemoryRequiresIncludedSubsystem() throws Exception {
        var config = mock(Config.class);
        when(config.getOptionalValue("sendium.dlr.storage.backend", String.class)).thenReturn(Optional.of("memory"));
        when(config.getOptionalValue("sendium.dlr.persistence.enabled", Boolean.class)).thenReturn(Optional.of(false));
        var factory = StandaloneOutboundPipeline.class.getDeclaredMethod("coordinator",
                MessageStorageProfile.class, Config.class, MeterRegistry.class);
        factory.setAccessible(true);
        assertThatThrownBy(() -> {
            try {
                Object unexpected = factory.invoke(null, new MessageStorageProfile("memory", "memory", "memory"), config, null);
                // The baseline returned an unprotected coordinator; do not leave it open during before-proof.
                if (unexpected instanceof OutboundCoordinator<?> coordinator) {
                    coordinator.close();
                }
            } catch (InvocationTargetException failure) {
                throw failure.getCause();
            }
        }).isInstanceOf(IllegalStateException.class).hasMessageContaining("selectedModeRequiresIncludedSubsystem");
    }
}
