package gr.cytech.sendium.core.smpp.server;

import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.worker.InMemoryDlrService;
import gr.cytech.sendium.core.worker.MessageState;
import gr.cytech.sendium.external.WorkerResourceProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InMemorySmppServerMessageStoreTest {

    @Mock
    private SmppServerWorker<StandardMessage> worker;

    @Mock
    private WorkerResourceProvider workerResources;

    @Mock
    private InMemoryDlrService dlrService;

    private InMemorySmppServerMessageStore messageStore;

    @BeforeEach
    void setUp() {
        when(worker.getWorkerResources()).thenReturn(workerResources);
        when(workerResources.getDlrService()).thenReturn(dlrService);
        when(worker.getMaxRetries()).thenReturn(5);

        messageStore = new InMemorySmppServerMessageStore(worker);
    }

    @Test
    void persistMessages_SavesStateForEachMessage() {
        List<InEvent<StandardMessage>> events = new ArrayList<>();

        StandardMessage msg1 = new StandardMessage();
        msg1.serial = "gw-1";
        msg1.owner_id = "account1";
        msg1.systemId = "sys1";
        msg1.from = "from1";
        msg1.to = "to1";

        StandardMessage msg2 = new StandardMessage();
        msg2.serial = "gw-2";
        msg2.owner_id = "account2";
        msg2.systemId = "sys2";
        msg2.from = "from2";
        msg2.to = "to2";

        InEvent<StandardMessage> event1 = new InEvent<>(msg1, null, 1, new Timestamp(System.currentTimeMillis()));
        InEvent<StandardMessage> event2 = new InEvent<>(msg2, null, 2, new Timestamp(System.currentTimeMillis()));

        events.add(event1);
        events.add(event2);

        messageStore.persistMessages(events);

        ArgumentCaptor<MessageState> captor = ArgumentCaptor.forClass(MessageState.class);
        verify(dlrService, times(2)).saveInitialState(captor.capture());
        assertEquals("account1", captor.getAllValues().get(0).getAccountId());
        assertEquals("sys1", captor.getAllValues().get(0).getSystemId());
        assertEquals("account2", captor.getAllValues().get(1).getAccountId());
        assertEquals("sys2", captor.getAllValues().get(1).getSystemId());
    }

    @Test
    void persistMessages_WithNullMessage_Skips() {
        List<InEvent<StandardMessage>> events = new ArrayList<>();

        InEvent<StandardMessage> event = new InEvent<>(null, null, 1, new Timestamp(System.currentTimeMillis()));

        events.add(event);

        messageStore.persistMessages(events);

        verify(dlrService, never()).saveInitialState(any(MessageState.class));
    }

    @Test
    void getMaxAttempts_DelegatesToWorker() {
        int result = messageStore.getMaxAttempts(true);

        assertEquals(5, result);
    }

    @Test
    void getMaxAttempts_DefaultsTo3_WhenNoWorker() {
        InMemorySmppServerMessageStore storeWithNullWorker = new InMemorySmppServerMessageStore(null);

        int result = storeWithNullWorker.getMaxAttempts(true);

        assertEquals(3, result);
    }

    @Test
    void markAsUnpushed_Dlr_SavesToDlrService() {
        StandardMessage msg = new StandardMessage();
        msg.type = StandardMessage.MSG_DLR;
        when(dlrService.saveUnpushedDlr(msg)).thenReturn(true);

        boolean result = messageStore.markAsUnpushed(msg);

        assertTrue(result);
        verify(dlrService).saveUnpushedDlr(msg);
    }

    @Test
    void markAsUnpushed_NonDlr_ReturnsFalse() {
        StandardMessage msg = new StandardMessage();
        msg.type = StandardMessage.MSG_TEXT;

        boolean result = messageStore.markAsUnpushed(msg);

        assertFalse(result);
        verify(dlrService, never()).saveUnpushedDlr(any());
    }

    @Test
    void onClientConnected_ReEnqueuesAndRemovesMatchingDlrs() {
        StandardMessage dlr = new StandardMessage();
        dlr.type = StandardMessage.MSG_DLR;
        dlr.owner_id = "account1";
        dlr.systemId = "sys1";
        when(dlrService.claimUnpushedDlrs("sys1")).thenReturn(List.of(dlr));
        when(worker.enqueueNoExceptions(dlr)).thenReturn(true);

        messageStore.onClientConnected("sys1");

        verify(worker).enqueueNoExceptions(dlr);
        verify(dlrService).removeUnpushedDlr(dlr);
    }

    @Test
    void onClientConnected_LeavesDlrStoredWhenReEnqueueFails() {
        StandardMessage dlr = new StandardMessage();
        dlr.type = StandardMessage.MSG_DLR;
        when(dlrService.claimUnpushedDlrs("sys1")).thenReturn(List.of(dlr));
        when(worker.enqueueNoExceptions(dlr)).thenReturn(false);

        messageStore.onClientConnected("sys1");

        verify(dlrService, never()).removeUnpushedDlr(any());
        verify(dlrService).releaseUnpushedDlrClaim(dlr);
    }
}
