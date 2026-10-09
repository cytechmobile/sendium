package gr.cytech.sendium.app.storage;

import gr.cytech.sendium.core.AbstractOutWorker;
import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.storage.OutboundStorageException;
import gr.cytech.sendium.routing.RoutingLookupResult;
import gr.cytech.sendium.routing.StandardRoutingManager;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StandaloneOutboundConfigurationTest {
    @Test
    void invalidMemoryBoundsFailBeforeReturningAnAdmissionCoordinator() {
        for (String setting : List.of("pending.capacity", "router-queue.capacity", "selection-batch-size")) {
            var config = mock(Config.class);
            when(config.getOptionalValue(anyString(), eq(Integer.class))).thenReturn(Optional.empty());
            String key = "sendium.message." + setting;
            when(config.getOptionalValue(key, Integer.class)).thenReturn(Optional.of(0));
            assertThatThrownBy(() -> StandaloneOutboundPipeline.coordinator(
                    new MessageStorageProfile("memory", "memory", "memory"), config))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(key).hasMessageContaining("positive");
        }
    }

    @Test
    void unsupportedLegacyDestinationIsRejectedRatherThanMistakenForTerminalExecution() throws Exception {
        var routing = mock(StandardRoutingManager.class);
        var unsupported = mock(AbstractOutWorker.class);
        when(routing.lookupForLifecycle(any())).thenReturn(new RoutingLookupResult(List.of(unsupported), true));
        var boundary = new StandaloneOutboundPipeline.LifecycleRouting(routing);
        assertThatThrownBy(() -> boundary.lookupForLifecycle(new StandardMessage()))
                .isInstanceOfSatisfying(OutboundStorageException.class, failure ->
                        assertThat(failure.reason()).isEqualTo(OutboundStorageException.Reason.UNSUPPORTED));
    }
}
