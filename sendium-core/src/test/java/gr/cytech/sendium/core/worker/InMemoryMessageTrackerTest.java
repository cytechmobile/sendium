package gr.cytech.sendium.core.worker;

import gr.cytech.sendium.core.AbstractOutWorker;
import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.external.WorkerResourceProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)

@ExtendWith(MockitoExtension.class)
class InMemoryMessageTrackerTest {

    @Mock
    private AbstractOutWorker<StandardMessage> outWorker;

    @Mock
    private WorkerResourceProvider workerResources;

    @Mock
    private InMemoryDlrService dlrService;

    private InMemoryMessageTracker tracker;

    @BeforeEach
    void setUp() {
        when(outWorker.getWorkerResources()).thenReturn(workerResources);
        when(workerResources.getDlrService()).thenReturn(dlrService);
        when(outWorker.getType()).thenReturn("testWorker");

        tracker = new InMemoryMessageTracker(outWorker);
    }

    @Test
    void updateSendStatusAndExtID_WithValidIds_Returns1() {
        StandardMessage pMsg = new StandardMessage();
        pMsg.serial = "gw-123";

        int result = tracker.updateSendStatusAndExtID("gw-123", pMsg, "op-456");

        assertEquals(1, result);
        verify(dlrService).linkOperatorId("gw-123", "op-456");
    }

    @Test
    void updateSendStatusAndExtID_WithNullSmsid_Returns0() {
        StandardMessage pMsg = new StandardMessage();
        pMsg.serial = null;

        int result = tracker.updateSendStatusAndExtID(null, pMsg, "op-456");

        assertEquals(0, result);
        verify(dlrService, never()).linkOperatorId(any(), any());
    }

    @Test
    void updateSendStatusAndExtID_WithNullSmscid_Returns0() {
        StandardMessage pMsg = new StandardMessage();
        pMsg.serial = "gw-123";

        int result = tracker.updateSendStatusAndExtID("gw-123", pMsg, null);

        assertEquals(0, result);
        verify(dlrService, never()).linkOperatorId(any(), any());
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
    void createAndEnqueueDLR_KnownMessage_ResolvesFromDlrService() {
        MessageState state = new MessageState("gw-123", "systemId", "from", "to", null);
        when(dlrService.resolveAndRemoveDlr("op-456", 0)).thenReturn(java.util.Optional.of(state));

        tracker.createAndEnqueueDLR(1, "op-456", "gw-123", "from", "to", "test body", 0, "0", new HashMap<>());

        verify(dlrService).resolveAndRemoveDlr("op-456", 0);
    }

    @Test
    void createAndEnqueueDLR_UnknownMessage_DoesNotEnqueue() {
        when(dlrService.resolveAndRemoveDlr("unknown", 0)).thenReturn(java.util.Optional.empty());

        tracker.createAndEnqueueDLR(1, "unknown", "gw-123", "from", "to", "test body", 0, "0", new HashMap<>());

        verify(dlrService).resolveAndRemoveDlr("unknown", 0);
    }

    @Test
    void getDlrQueueSize_ReturnsQueueSize() {
        assertEquals(0, tracker.getDlrQueueSize());
    }
}