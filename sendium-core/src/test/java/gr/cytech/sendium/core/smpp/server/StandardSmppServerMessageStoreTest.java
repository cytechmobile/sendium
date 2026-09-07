package gr.cytech.sendium.core.smpp.server;

import com.cloudhopper.smpp.pdu.SubmitSm;
import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.worker.DlrService;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StandardSmppServerMessageStoreTest {

    @Mock
    private SmppServerWorker<StandardMessage> worker;

    @Mock
    private WorkerResourceProvider workerResources;

    @Mock
    private DlrService dlrService;

    private StandardSmppServerMessageStore messageStore;

    @BeforeEach
    void setUp() {
        when(worker.getWorkerResources()).thenReturn(workerResources);
        when(workerResources.isDlrPersistenceEnabled()).thenReturn(true);
        when(workerResources.getDlrService()).thenReturn(dlrService);
        when(worker.getMaxRetries()).thenReturn(5);
        when(worker.isForwardDlrs()).thenReturn(true);

        messageStore = new StandardSmppServerMessageStore(worker);
    }

    @Test
    void persistMessages_HandsIngressBatchToWorkerWithoutDlrStorage() {
        List<InEvent<StandardMessage>> events = List.of(
                event("first", new SubmitSm()),
                event("internal", null),
                event("second", new SubmitSm()));

        messageStore.processIngressMessages(events);

        verify(worker).handleIngressMessages(events);
        verifyNoInteractions(dlrService);
    }

    @Test
    void durableDlrAttemptsFollowPersistenceBoundary() {
        when(workerResources.isDlrPersistenceEnabled()).thenReturn(false, true);

        assertFalse(messageStore.tracksDlrDeliveryAttempts());
        assertTrue(messageStore.tracksDlrDeliveryAttempts());
    }

    @Test
    void markAsUnpushedFallsBackWhenPersistenceIsDisabled() {
        when(workerResources.isDlrPersistenceEnabled()).thenReturn(false);
        StandardMessage dlr = new StandardMessage();
        dlr.type = StandardMessage.MSG_DLR;

        assertFalse(messageStore.markAsUnpushed(dlr));
    }

    @Test
    void onClientConnected_ReconstructsAndEnqueuesPendingDlrWithoutCompletingIt() throws Exception {
        MessageState state = pendingState();
        when(dlrService.listPendingSmppDeliveries("sys1")).thenReturn(List.of(state));

        messageStore.onClientConnected("sys1");

        ArgumentCaptor<StandardMessage> captor = ArgumentCaptor.forClass(StandardMessage.class);
        verify(worker).enqueue(captor.capture());
        StandardMessage replay = captor.getValue();
        assertAll(
                () -> assertEquals(StandardMessage.MSG_DLR, replay.type),
                () -> assertEquals("gw-1", replay.serial),
                () -> assertEquals("destination", replay.from),
                () -> assertEquals("source", replay.to),
                () -> assertEquals(StandardMessage.DLR_STAT_UNDELIV, replay.state),
                () -> assertEquals("42", replay.errcode),
                () -> assertEquals("account1", replay.owner_id),
                () -> assertEquals("sys1", replay.systemId),
                () -> assertEquals(List.of("part-1", "part-2"), replay.reassembledParts));
        verify(dlrService, never()).completeDelivery(anyString(), anyInt());
    }

    @Test
    void onClientConnected_WhenEnqueueFailsRetainsPendingDlr() throws Exception {
        MessageState state = pendingState();
        when(dlrService.listPendingSmppDeliveries("sys1")).thenReturn(List.of(state));
        doThrow(new InterruptedException("queue stopped")).when(worker).enqueue(any());

        messageStore.onClientConnected("sys1");

        verify(dlrService, never()).completeDelivery(anyString(), anyInt());
        verify(dlrService, never()).retryDelivery(anyString(), anyInt(), anyString(), anyLong());
        assertTrue(Thread.interrupted());
    }

    @Test
    void persistMessages_PassesNullMessageToWorkerForIngressHandling() {
        List<InEvent<StandardMessage>> events = List.of(
                new InEvent<>(null, null, 1, new Timestamp(System.currentTimeMillis())));

        messageStore.processIngressMessages(events);

        verify(worker).handleIngressMessages(events);
        verifyNoInteractions(dlrService);
    }

    @Test
    void getMaxAttempts_DelegatesToWorker() {
        int result = messageStore.getMaxAttempts(true);

        assertEquals(5, result);
    }

    @Test
    void getMaxAttempts_DefaultsTo3_WhenNoWorker() {
        StandardSmppServerMessageStore storeWithNullWorker = new StandardSmppServerMessageStore(null);

        int result = storeWithNullWorker.getMaxAttempts(true);

        assertEquals(3, result);
    }

    private InEvent<StandardMessage> event(String serial, SubmitSm submitSm) {
        StandardMessage message = new StandardMessage();
        message.serial = serial;
        return new InEvent<>(message, submitSm, 1, new Timestamp(System.currentTimeMillis()));
    }

    private MessageState pendingState() {
        MessageState state = new MessageState(
                "gw-1", "account1", "sys1", "source", "destination", null);
        state.setDlrState(StandardMessage.DLR_STAT_UNDELIV);
        state.setErrorCode("42");
        state.setReassembledParts(List.of("part-1", "part-2"));
        state.setDeliveryChannel(MessageState.DeliveryChannel.SMPP);
        state.setDeliveryStatus(MessageState.DeliveryStatus.PENDING);
        return state;
    }

}
