package gr.cytech.sendium.core.smpp.server;

import com.cloudhopper.smpp.pdu.SubmitSm;
import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.worker.DlrService;
import gr.cytech.sendium.core.worker.DlrStorageException;
import gr.cytech.sendium.core.worker.MessageState;
import gr.cytech.sendium.external.WorkerResourceProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
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
    private DlrService dlrService;

    private InMemorySmppServerMessageStore messageStore;

    @BeforeEach
    void setUp() {
        when(worker.getWorkerResources()).thenReturn(workerResources);
        when(workerResources.getDlrService()).thenReturn(dlrService);
        when(worker.getMaxRetries()).thenReturn(5);

        messageStore = new InMemorySmppServerMessageStore(worker);
    }

    @Test
    void persistMessages_SavesStatesAsOneBatchBeforeNotifyingWorker() {
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

        InEvent<StandardMessage> event1 = new InEvent<>(msg1, new SubmitSm(), 1,
                new Timestamp(System.currentTimeMillis()));
        InEvent<StandardMessage> event2 = new InEvent<>(msg2, new SubmitSm(), 2,
                new Timestamp(System.currentTimeMillis()));

        events.add(event1);
        events.add(event2);

        messageStore.persistMessages(events);

        ArgumentCaptor<List<MessageState>> captor = ArgumentCaptor.forClass(List.class);
        InOrder order = inOrder(dlrService, worker);
        order.verify(dlrService).saveInitialStates(captor.capture());
        order.verify(worker).handlePersistedMessages(events);
        assertEquals("account1", captor.getValue().get(0).getAccountId());
        assertEquals("sys1", captor.getValue().get(0).getSystemId());
        assertEquals("account2", captor.getValue().get(1).getAccountId());
        assertEquals("sys2", captor.getValue().get(1).getSystemId());
    }

    @Test
    void persistMessages_SavesReassembledPartIds() {
        StandardMessage msg = new StandardMessage();
        msg.serial = "gw-1";
        msg.owner_id = "account1";
        msg.systemId = "sys1";
        msg.from = "from1";
        msg.to = "to1";
        msg.reassembledParts = new ArrayList<>(List.of("part-1", "part-2"));

        messageStore.persistMessages(List.of(new InEvent<>(msg, null, 1, new Timestamp(System.currentTimeMillis()))));

        ArgumentCaptor<List<MessageState>> captor = ArgumentCaptor.forClass(List.class);
        verify(dlrService).saveInitialStates(captor.capture());
        assertEquals(List.of("part-1", "part-2"), captor.getValue().getFirst().getReassembledParts());
    }

    @Test
    void persistMessages_WithNullMessage_Skips() {
        List<InEvent<StandardMessage>> events = new ArrayList<>();

        InEvent<StandardMessage> event = new InEvent<>(null, null, 1, new Timestamp(System.currentTimeMillis()));

        events.add(event);

        messageStore.persistMessages(events);

        verify(dlrService).saveInitialStates(List.of());
        verify(worker).handlePersistedMessages(events);
    }

    @Test
    void persistMessages_WhenStorageFails_NotifiesWorkerFailure() {
        StandardMessage msg = new StandardMessage();
        msg.serial = "gw-1";
        List<InEvent<StandardMessage>> events = List.of(
                new InEvent<>(msg, new SubmitSm(), 1, new Timestamp(System.currentTimeMillis())));
        doThrow(new DlrStorageException("database details"))
                .when(dlrService).saveInitialStates(anyList());

        assertFalse(messageStore.persistMessages(events).resultNow());

        verify(worker).handleMessagePersistenceFailure(events);
        verify(worker, never()).handlePersistedMessages(anyList());
    }

    @Test
    void persistMessages_IsolatesInternalEventsWithoutReorderingCallbacks() {
        InEvent<StandardMessage> firstClient = event("first-client", new SubmitSm());
        InEvent<StandardMessage> internal = event("internal", null);
        InEvent<StandardMessage> secondClient = event("second-client", new SubmitSm());

        messageStore.persistMessages(List.of(firstClient, internal, secondClient));

        InOrder order = inOrder(dlrService, worker);
        order.verify(dlrService).saveInitialStates(anyList());
        order.verify(worker).handlePersistedMessages(List.of(firstClient));
        order.verify(dlrService).saveInitialStates(anyList());
        order.verify(worker).handlePersistedMessages(List.of(internal));
        order.verify(dlrService).saveInitialStates(anyList());
        order.verify(worker).handlePersistedMessages(List.of(secondClient));
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

    private InEvent<StandardMessage> event(String serial, SubmitSm submitSm) {
        StandardMessage message = new StandardMessage();
        message.serial = serial;
        return new InEvent<>(message, submitSm, 1, new Timestamp(System.currentTimeMillis()));
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
