package gr.cytech.sendium.core.storage.pending;

import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.outbound.DefaultOutboundCoordinator;
import gr.cytech.sendium.core.outbound.OutboundWork.SourceId;
import gr.cytech.sendium.core.storage.OutboundStage;
import gr.cytech.sendium.core.storage.OutboundStorageException;
import gr.cytech.sendium.core.storage.PendingMessageStore;
import gr.cytech.sendium.core.storage.RoutedWorkStore;
import gr.cytech.sendium.core.storage.SelectedRouterStore;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PendingAdmissionCoordinatorTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @SuppressWarnings("unchecked")
    void propagatesAcceptedHandle(boolean held) {
        PendingMessageStore<StandardMessage> pending = mock(PendingMessageStore.class);
        SelectedRouterStore<StandardMessage> router = mock(SelectedRouterStore.class);
        RoutedWorkStore<StandardMessage> routed = mock(RoutedWorkStore.class);
        when(pending.status()).thenReturn(status(OutboundStage.State.NEW), status(OutboundStage.State.READY));
        when(router.status()).thenReturn(status(OutboundStage.State.NEW), status(OutboundStage.State.READY));
        when(routed.status()).thenReturn(status(OutboundStage.State.NEW), status(OutboundStage.State.READY));
        SourceId source = new SourceId(UUID.randomUUID());
        StandardMessage message = new StandardMessage();
        message.serial = source.value().toString();
        when(pending.admit(source, message)).thenReturn(source);
        when(pending.admitHeld(source, message)).thenReturn(source);
        try (var coordinator = new DefaultOutboundCoordinator<>(pending, router, routed)) {
            coordinator.start();
            assertThat(held ? coordinator.acceptHeld(source, message) : coordinator.accept(source, message)).isEqualTo(source);
            if (held) {
                verify(pending).admitHeld(source, message);
            } else {
                verify(pending).admit(source, message);
            }
            when(pending.admit(any(), any())).thenReturn(null);
            assertThatThrownBy(() -> coordinator.accept(new SourceId(UUID.randomUUID()), message))
                    .isInstanceOf(OutboundStorageException.class);
            when(pending.admit(any(), any())).thenReturn(new SourceId(UUID.randomUUID()));
            assertThatThrownBy(() -> coordinator.accept(source, message))
                    .isInstanceOf(OutboundStorageException.class);
        }
    }

    private static OutboundStage.Status status(OutboundStage.State state) {
        return new OutboundStage.Status("memory", false, state);
    }
}
