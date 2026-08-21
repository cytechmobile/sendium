package gr.cytech.sendium.core.worker;

import gr.cytech.sendium.core.message.StandardMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DlrServiceTest {
    @Mock
    DlrStorage storage;

    @InjectMocks
    DlrService service;

    @Test
    void resolveDlrPassesExactDeliveredOutcome() {
        MessageState state = stateWithCallback();
        when(storage.resolveDlr("provider-1", "provider-message-1", MessageState.MessageStatus.DELIVERED,
                StandardMessage.DLR_STAT_DELIVRD, "007")).thenReturn(Optional.of(state));

        Optional<MessageState> result = service.resolveDlr(
                "provider-1", "provider-message-1", StandardMessage.DLR_STAT_DELIVRD, "007");

        assertThat(result).containsSame(state);
        verify(storage).resolveDlr("provider-1", "provider-message-1", MessageState.MessageStatus.DELIVERED,
                StandardMessage.DLR_STAT_DELIVRD, "007");
    }

    @Test
    void resolveDlrPreservesFinalOnlyGuard() {
        assertThat(service.resolveDlr(
                "provider-1", "accepted", StandardMessage.DLR_STAT_ACCEPTD, "000")).isEmpty();
        assertThat(service.resolveDlr(
                "provider-1", "buffered", StandardMessage.DLR_STAT_BUFFRED, "000")).isEmpty();

        verifyNoInteractions(storage);
    }

    @Test
    void resolveDlrMapsTerminalFailureAndPassesExactError() {
        MessageState state = stateWithoutCallback();
        when(storage.resolveDlr("provider-1", "provider-message-1", MessageState.MessageStatus.FAILED,
                StandardMessage.DLR_STAT_REJECTD, "  exact  ")).thenReturn(Optional.of(state));

        Optional<MessageState> result = service.resolveDlr(
                "provider-1", "provider-message-1", StandardMessage.DLR_STAT_REJECTD, "  exact  ");

        assertThat(result).containsSame(state);
    }

    @Test
    void resolveDlrReturnsMissingState() {
        when(storage.resolveDlr("provider-1", "unknown", MessageState.MessageStatus.DELIVERED,
                StandardMessage.DLR_STAT_SEEN, null)).thenReturn(Optional.empty());

        Optional<MessageState> result = service.resolveDlr(
                "provider-1", "unknown", StandardMessage.DLR_STAT_SEEN, null);

        assertThat(result).isEmpty();
    }

    private MessageState stateWithCallback() {
        return new MessageState("gateway-1", "account", "system", "source", "destination",
                "https://example.test/dlr");
    }

    private MessageState stateWithoutCallback() {
        return new MessageState("gateway-1", "account", "system", "source", "destination", null);
    }
}
