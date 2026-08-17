package gr.cytech.sendium.core.worker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DlrServiceTest {
    @Mock
    DlrStorage storage;

    @Mock
    ForwardDlrService forwardDlrService;

    @InjectMocks
    DlrService service;

    @Test
    void resolveAndRemoveDlrMapsDeliveredStateAndForwardsCallback() {
        MessageState state = stateWithCallback();
        when(storage.resolveAndRemoveDlr("operator-1", MessageState.MessageStatus.DELIVERED))
                .thenReturn(Optional.of(state));

        Optional<MessageState> result = service.resolveAndRemoveDlr("operator-1", 1);

        assertThat(result).containsSame(state);
        verify(forwardDlrService).forwardDlr(state);
    }

    @Test
    void resolveAndRemoveDlrMapsAcceptedState() {
        MessageState state = stateWithoutCallback();
        when(storage.resolveAndRemoveDlr("operator-1", MessageState.MessageStatus.ACCEPTED))
                .thenReturn(Optional.of(state));

        Optional<MessageState> result = service.resolveAndRemoveDlr("operator-1", 9);

        assertThat(result).containsSame(state);
        verify(forwardDlrService, never()).forwardDlr(state);
    }

    @Test
    void resolveAndRemoveDlrMapsUnknownStateToFailed() {
        MessageState state = stateWithoutCallback();
        when(storage.resolveAndRemoveDlr("operator-1", MessageState.MessageStatus.FAILED))
                .thenReturn(Optional.of(state));

        Optional<MessageState> result = service.resolveAndRemoveDlr("operator-1", 0);

        assertThat(result).containsSame(state);
    }

    @Test
    void resolveAndRemoveDlrDoesNotForwardMissingState() {
        when(storage.resolveAndRemoveDlr("unknown", MessageState.MessageStatus.DELIVERED))
                .thenReturn(Optional.empty());

        Optional<MessageState> result = service.resolveAndRemoveDlr("unknown", 15);

        assertThat(result).isEmpty();
        verify(forwardDlrService, never()).forwardDlr(org.mockito.ArgumentMatchers.any());
    }

    private MessageState stateWithCallback() {
        return new MessageState("gateway-1", "account", "system", "source", "destination",
                "https://example.test/dlr");
    }

    private MessageState stateWithoutCallback() {
        return new MessageState("gateway-1", "account", "system", "source", "destination", null);
    }
}
