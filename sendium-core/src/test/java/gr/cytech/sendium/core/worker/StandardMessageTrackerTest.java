package gr.cytech.sendium.core.worker;

import gr.cytech.sendium.core.AbstractOutWorker;
import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.external.WorkerResourceProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StandardMessageTrackerTest {

    @Mock
    private AbstractOutWorker<StandardMessage> outWorker;

    @Mock
    private WorkerResourceProvider workerResources;

    @Mock
    private DlrService dlrService;

    private StandardMessageTracker tracker;

    @BeforeEach
    void setUp() {
        when(outWorker.getWorkerResources()).thenReturn(workerResources);
        when(workerResources.isDlrPersistenceEnabled()).thenReturn(true);
        when(workerResources.getDlrService()).thenReturn(dlrService);
        when(outWorker.getType()).thenReturn("testWorker");
        when(outWorker.getDlrProviderName()).thenReturn("provider-1");

        tracker = new StandardMessageTracker(outWorker);
    }

    @Test
    void updateSendStatusAndExtID_WithValidIds_Returns1() {
        StandardMessage pMsg = new StandardMessage();
        pMsg.serial = "gw-123";

        int result = tracker.updateSendStatusAndExtID("gw-123", pMsg, "provider-message-456");

        assertEquals(1, result);
        verify(dlrService).linkProviderMessageId("gw-123", "provider-1", "provider-message-456");
    }

    @Test
    void updateSendStatusAndExtID_WithNullGatewayMessageId_Returns0() {
        StandardMessage pMsg = new StandardMessage();
        pMsg.serial = null;

        int result = tracker.updateSendStatusAndExtID(null, pMsg, "provider-message-456");

        assertEquals(0, result);
        verify(dlrService, never()).linkProviderMessageId(any(), any(), any());
    }

    @Test
    void updateSendStatusAndExtID_WithNullProviderMessageId_Returns0() {
        StandardMessage pMsg = new StandardMessage();
        pMsg.serial = "gw-123";

        int result = tracker.updateSendStatusAndExtID("gw-123", pMsg, null);

        assertEquals(0, result);
        verify(dlrService, never()).linkProviderMessageId(any(), any(), any());
    }

    @Test
    void updateSendStatusAndExtID_WhenStorageFails_PropagatesToProtocolBoundary() {
        StandardMessage pMsg = new StandardMessage();
        pMsg.serial = "gw-123";
        doThrow(new DlrStorageException("Failed to link provider DLR ID"))
                .when(dlrService).linkProviderMessageId("gw-123", "provider-1", "provider-message-456");

        assertThrows(DlrStorageException.class,
                () -> tracker.updateSendStatusAndExtID("gw-123", pMsg, "provider-message-456"));
    }

    @Test
    void updateSendStatusAndExtID_WhenPersistenceDisabled_SkipsLinking() {
        when(workerResources.isDlrPersistenceEnabled()).thenReturn(false);
        StandardMessage pMsg = new StandardMessage();
        pMsg.serial = "gw-123";

        int result = tracker.updateSendStatusAndExtID("gw-123", pMsg, "provider-message-456");

        assertEquals(0, result);
        verify(workerResources, never()).getDlrService();
    }

    @Test
    void createAndEnqueueDLR_WhenStorageFails_PropagatesToProtocolBoundary() throws InterruptedException {
        when(dlrService.resolveAndRemoveDlr("provider-1", "provider-message-456", 0))
                .thenThrow(new DlrStorageException("Failed to resolve DLR state"));

        assertThrows(DlrStorageException.class, () -> tracker.createAndEnqueueDLR(
                1, "provider-message-456", "gw-123", "from", "to", "test body", 0, "0", new HashMap<>()));

        verify(outWorker, never()).enqueueToRouter(any());
    }

    @Test
    void createAndEnqueueDLR_WhenPersistenceDisabled_DoesNotResolve() throws InterruptedException {
        when(workerResources.isDlrPersistenceEnabled()).thenReturn(false);

        tracker.createAndEnqueueDLR(
                1, "provider-message-456", "gw-123", "from", "to", "test body", 0, "0", new HashMap<>());

        verify(workerResources, never()).getDlrService();
        verify(outWorker, never()).enqueueToRouter(any());
    }

    @Test
    void getHashedMessageID_GeneratesMd5() {
        String result = tracker.getHashedMessageID("msg-123");

        assertNotNull(result);
        assertFalse(result.isEmpty());
        assertEquals(32, result.length());
    }

    @Test
    void getHashedMessageID_NullInput_ReturnsEmpty() {
        String result = tracker.getHashedMessageID(null);

        assertEquals("", result);
    }

    @Test
    void createAndEnqueueDLR_KnownMessage_ResolvesFromDlrService() throws InterruptedException {
        MessageState state = new MessageState("gw-123", "accountId", "systemId", "from", "to", null);
        when(dlrService.resolveAndRemoveDlr("provider-1", "provider-message-456", 0))
                .thenReturn(java.util.Optional.of(state));

        tracker.createAndEnqueueDLR(
                1, "provider-message-456", "gw-123", "from", "to", "test body", 0, "0", new HashMap<>());

        verify(dlrService).resolveAndRemoveDlr("provider-1", "provider-message-456", 0);
        ArgumentCaptor<StandardMessage> captor = ArgumentCaptor.forClass(StandardMessage.class);
        verify(outWorker).enqueueToRouter(captor.capture());
        assertEquals("accountId", captor.getValue().owner_id);
        assertEquals("systemId", captor.getValue().systemId);
    }

    @Test
    void createAndEnqueueDLR_KnownReassembledMessage_RestoresPartIds() throws InterruptedException {
        MessageState state = new MessageState("gw-123", "accountId", "systemId", "from", "to", null);
        state.setReassembledParts(new ArrayList<>(List.of("part-1", "part-2")));
        when(dlrService.resolveAndRemoveDlr("provider-1", "provider-message-456", 1))
                .thenReturn(java.util.Optional.of(state));

        tracker.createAndEnqueueDLR(
                1, "provider-message-456", "gw-123", "from", "to", "test body", 1, "0", new HashMap<>());

        ArgumentCaptor<StandardMessage> captor = ArgumentCaptor.forClass(StandardMessage.class);
        verify(outWorker).enqueueToRouter(captor.capture());
        assertEquals(List.of("part-1", "part-2"), captor.getValue().reassembledParts);
    }

    @Test
    void createAndEnqueueDLR_UnknownMessage_DoesNotEnqueue() {
        when(dlrService.resolveAndRemoveDlr("provider-1", "unknown", 0))
                .thenReturn(java.util.Optional.empty());

        tracker.createAndEnqueueDLR(1, "unknown", "gw-123", "from", "to", "test body", 0, "0", new HashMap<>());

        verify(dlrService).resolveAndRemoveDlr("provider-1", "unknown", 0);
    }

}
