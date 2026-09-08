package gr.cytech.sendium.core.worker;

import gr.cytech.sendium.core.AbstractOutWorker;
import gr.cytech.sendium.core.message.DlrReturnMetadata;
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
        pMsg.reassembledParts = new ArrayList<>(List.of("part-1", "part-2"));
        pMsg.dlrReturnMetadata = DlrReturnMetadata.smpp(
                "accountId", "systemId", "from", "to");

        int result = tracker.updateSendStatusAndExtID("gw-123", pMsg, "provider-message-456");

        assertEquals(1, result);
        ArgumentCaptor<MessageState> captor = ArgumentCaptor.forClass(MessageState.class);
        verify(dlrService).recordProviderAccepted(
                captor.capture(), eq("provider-1"), eq("provider-message-456"));
        assertEquals("gw-123", captor.getValue().getGatewayMsgId());
        assertEquals("accountId", captor.getValue().getAccountId());
        assertEquals("systemId", captor.getValue().getSystemId());
        assertEquals(MessageState.DeliveryChannel.SMPP, captor.getValue().getDeliveryChannel());
        assertEquals(List.of("part-1", "part-2"), captor.getValue().getReassembledParts());
    }

    @Test
    void updateSendStatusAndExtID_WithNullGatewayMessageId_Returns0() {
        StandardMessage pMsg = new StandardMessage();
        pMsg.serial = null;

        int result = tracker.updateSendStatusAndExtID(null, pMsg, "provider-message-456");

        assertEquals(0, result);
        verify(dlrService, never()).recordProviderAccepted(any(), any(), any());
    }

    @Test
    void updateSendStatusAndExtID_WithNullProviderMessageId_Returns0() {
        StandardMessage pMsg = new StandardMessage();
        pMsg.serial = "gw-123";

        int result = tracker.updateSendStatusAndExtID("gw-123", pMsg, null);

        assertEquals(0, result);
        verify(dlrService, never()).recordProviderAccepted(any(), any(), any());
    }

    @Test
    void updateSendStatusAndExtID_WhenStorageFails_PropagatesToProtocolBoundary() {
        StandardMessage pMsg = new StandardMessage();
        pMsg.serial = "gw-123";
        pMsg.dlrReturnMetadata = DlrReturnMetadata.smpp(
                "accountId", "systemId", "from", "to");
        doThrow(new DlrStorageException("Failed to link provider DLR ID"))
                .when(dlrService).recordProviderAccepted(
                        any(MessageState.class), eq("provider-1"), eq("provider-message-456"));

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
    void updateSendStatusAndExtID_WithoutReturnMetadata_SkipsPersistence() {
        StandardMessage pMsg = new StandardMessage();
        pMsg.serial = "gw-123";

        int result = tracker.updateSendStatusAndExtID("gw-123", pMsg, "provider-message-456");

        assertEquals(0, result);
        verify(dlrService, never()).recordProviderAccepted(any(), any(), any());
    }

    @Test
    void submissionFailure_PersistsAndEnqueuesSmppDlr() throws InterruptedException {
        StandardMessage message = new StandardMessage();
        message.serial = "gw-123";
        message.dlrReturnMetadata = DlrReturnMetadata.smpp(
                "accountId", "systemId", "from", "to");
        MessageState rejected = new MessageState(
                "gw-123", "accountId", "systemId", "from", "to", null);
        rejected.setDeliveryChannel(MessageState.DeliveryChannel.SMPP);
        when(dlrService.recordProviderRejected(any(), eq("provider-1"), eq("provider-message-456"),
                eq(StandardMessage.DLR_STAT_FAILED), eq("22"))).thenReturn(java.util.Optional.of(rejected));

        tracker.createAndEnqueueSubmissionFailure(message, "provider-message-456", "hash",
                "5", StandardMessage.DLR_STAT_FAILED, "22", null);

        ArgumentCaptor<StandardMessage> captor = ArgumentCaptor.forClass(StandardMessage.class);
        verify(outWorker).enqueueToRouter(captor.capture());
        assertEquals(StandardMessage.MSG_DLR, captor.getValue().type);
        assertEquals("gw-123", captor.getValue().serial);
        assertEquals("5", captor.getValue().body);
        assertEquals("22", captor.getValue().errcode);
    }

    @Test
    void submissionFailure_PersistsHttpDlrWithoutRouterEnqueue() throws InterruptedException {
        StandardMessage message = new StandardMessage();
        message.serial = "gw-http";
        message.dlrReturnMetadata = DlrReturnMetadata.http(
                "accountId", "from", "to", "https://example.test/dlr");
        MessageState rejected = new MessageState(
                "gw-http", "accountId", "accountId", "from", "to", "https://example.test/dlr");
        rejected.setDeliveryChannel(MessageState.DeliveryChannel.HTTP);
        when(dlrService.recordProviderRejected(any(), eq("provider-1"), isNull(),
                eq(StandardMessage.DLR_STAT_FAILED), eq("22"))).thenReturn(java.util.Optional.of(rejected));

        tracker.createAndEnqueueSubmissionFailure(message, null, "",
                "5", StandardMessage.DLR_STAT_FAILED, "22", null);

        verify(outWorker, never()).enqueueToRouter(any());
    }

    @Test
    void createAndEnqueueDLR_WhenStorageFails_PropagatesToProtocolBoundary() throws InterruptedException {
        when(dlrService.resolveDlr("provider-1", "provider-message-456", 0, "0"))
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
    void createAndEnqueueDLR_SmppMessage_ResolvesAndEnqueues() throws InterruptedException {
        MessageState state = new MessageState("gw-123", "accountId", "systemId", "from", "to", null);
        state.setDeliveryChannel(MessageState.DeliveryChannel.SMPP);
        when(dlrService.resolveDlr("provider-1", "provider-message-456", 0, "0"))
                .thenReturn(java.util.Optional.of(state));

        tracker.createAndEnqueueDLR(
                1, "provider-message-456", "gw-123", "from", "to", "test body", 0, "0", new HashMap<>());

        verify(dlrService).resolveDlr("provider-1", "provider-message-456", 0, "0");
        ArgumentCaptor<StandardMessage> captor = ArgumentCaptor.forClass(StandardMessage.class);
        verify(outWorker).enqueueToRouter(captor.capture());
        assertEquals("accountId", captor.getValue().owner_id);
        assertEquals("systemId", captor.getValue().systemId);
    }

    @Test
    void createAndEnqueueDLR_KnownReassembledMessage_RestoresPartIds() throws InterruptedException {
        MessageState state = new MessageState("gw-123", "accountId", "systemId", "from", "to", null);
        state.setDeliveryChannel(MessageState.DeliveryChannel.SMPP);
        state.setReassembledParts(new ArrayList<>(List.of("part-1", "part-2")));
        when(dlrService.resolveDlr("provider-1", "provider-message-456", 1, "0"))
                .thenReturn(java.util.Optional.of(state));

        tracker.createAndEnqueueDLR(
                1, "provider-message-456", "gw-123", "from", "to", "test body", 1, "0", new HashMap<>());

        ArgumentCaptor<StandardMessage> captor = ArgumentCaptor.forClass(StandardMessage.class);
        verify(outWorker).enqueueToRouter(captor.capture());
        assertEquals(List.of("part-1", "part-2"), captor.getValue().reassembledParts);
    }

    @Test
    void createAndEnqueueDLR_HttpMessage_DoesNotEnqueue() throws InterruptedException {
        MessageState state = new MessageState("gw-http", "accountId", "systemId", "from", "to",
                "https://example.test/dlr");
        state.setDeliveryChannel(MessageState.DeliveryChannel.HTTP);
        when(dlrService.resolveDlr("provider-1", "provider-http", 1, "0"))
                .thenReturn(java.util.Optional.of(state));

        tracker.createAndEnqueueDLR(
                1, "provider-http", "gw-http", "from", "to", "test body", 1, "0", new HashMap<>());

        verify(outWorker, never()).enqueueToRouter(any());
    }

    @Test
    void createAndEnqueueDLR_NoneMessage_DoesNotEnqueue() throws InterruptedException {
        MessageState state = new MessageState("gw-none", "accountId", "systemId", "from", "to", null);
        state.setDeliveryChannel(MessageState.DeliveryChannel.NONE);
        when(dlrService.resolveDlr("provider-1", "provider-none", 1, "0"))
                .thenReturn(java.util.Optional.of(state));

        tracker.createAndEnqueueDLR(
                1, "provider-none", "gw-none", "from", "to", "test body", 1, "0", new HashMap<>());

        verify(outWorker, never()).enqueueToRouter(any());
    }

    @Test
    void createAndEnqueueDLR_UnknownMessage_DoesNotEnqueue() {
        when(dlrService.resolveDlr("provider-1", "unknown", 0, "0"))
                .thenReturn(java.util.Optional.empty());

        tracker.createAndEnqueueDLR(1, "unknown", "gw-123", "from", "to", "test body", 0, "0", new HashMap<>());

        verify(dlrService).resolveDlr("provider-1", "unknown", 0, "0");
    }

}
