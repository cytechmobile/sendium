package gr.cytech.sendium.core.worker;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryDlrServiceTest {

    private InMemoryDlrService dlrService;

    @BeforeEach
    void setUp() {
        dlrService = new InMemoryDlrService();
        dlrService.init();
    }

    @Test
    void saveInitialState_StoresInPrimaryStore() {
        MessageState state = new MessageState("gw-123", "systemId", "from", "to", null);

        dlrService.saveInitialState(state);

        assertEquals(1, dlrService.getPrimaryStoreSize());
    }

    @Test
    void saveInitialState_SetsTimestamp() {
        MessageState state = new MessageState("gw-123", "systemId", "from", "to", null);

        dlrService.saveInitialState(state);

        Optional<MessageState> retrieved = dlrService.getState("gw-123");
        assertTrue(retrieved.isPresent());
        assertTrue(retrieved.get().getTimestamp() > 0);
    }

    @Test
    void linkOperatorId_LinksCorrelation() {
        MessageState state = new MessageState("gw-123", "systemId", "from", "to", null);
        dlrService.saveInitialState(state);

        dlrService.linkOperatorId("gw-123", "op-456");

        assertEquals(1, dlrService.getCorrelationIndexSize());
    }

    @Test
    void linkOperatorId_UpdatesStatusToSent() {
        MessageState state = new MessageState("gw-123", "systemId", "from", "to", null);
        dlrService.saveInitialState(state);

        dlrService.linkOperatorId("gw-123", "op-456");

        Optional<MessageState> retrieved = dlrService.getState("gw-123");
        assertTrue(retrieved.isPresent());
        assertEquals(MessageState.MessageStatus.SENT, retrieved.get().getStatus());
    }

    @Test
    void resolveAndRemoveDlr_ReturnsAndRemoves() {
        MessageState state = new MessageState("gw-123", "systemId", "from", "to", null);
        dlrService.saveInitialState(state);
        dlrService.linkOperatorId("gw-123", "op-456");

        Optional<MessageState> result = dlrService.resolveAndRemoveDlr("op-456", 1);

        assertTrue(result.isPresent());
        assertEquals(0, dlrService.getPrimaryStoreSize());
        assertEquals(0, dlrService.getCorrelationIndexSize());
    }

    @Test
    void resolveAndRemoveDlr_MissingId_ReturnsEmpty() {
        Optional<MessageState> result = dlrService.resolveAndRemoveDlr("unknown", 1);

        assertTrue(result.isEmpty());
    }

    @Test
    void getState_ReturnsWrappedState() {
        MessageState state = new MessageState("gw-123", "systemId", "from", "to", null);
        dlrService.saveInitialState(state);

        Optional<MessageState> result = dlrService.getState("gw-123");

        assertTrue(result.isPresent());
        assertEquals("gw-123", result.get().getGatewayMsgId());
    }

    @Test
    void getState_MissingId_ReturnsEmpty() {
        Optional<MessageState> result = dlrService.getState("unknown");

        assertTrue(result.isEmpty());
    }

    @Test
    void markAsFailed_UpdatesStatusToFailed() {
        MessageState state = new MessageState("gw-123", "systemId", "from", "to", null);
        dlrService.saveInitialState(state);

        boolean result = dlrService.markAsFailed("gw-123");

        assertTrue(result);
        Optional<MessageState> updated = dlrService.getState("gw-123");
        assertTrue(updated.isPresent());
        assertEquals(MessageState.MessageStatus.FAILED, updated.get().getStatus());
    }

    @Test
    void markAsFailed_MissingId_ReturnsFalse() {
        boolean result = dlrService.markAsFailed("unknown");

        assertFalse(result);
    }

    @Test
    void getPrimaryStoreSize_ReturnsCount() {
        dlrService.saveInitialState(new MessageState("gw-1", "systemId", "from", "to", null));
        dlrService.saveInitialState(new MessageState("gw-2", "systemId", "from", "to", null));
        dlrService.saveInitialState(new MessageState("gw-3", "systemId", "from", "to", null));

        assertEquals(3, dlrService.getPrimaryStoreSize());
    }

    @Test
    void getCorrelationIndexSize_ReturnsCount() {
        dlrService.saveInitialState(new MessageState("gw-1", "systemId", "from", "to", null));
        dlrService.saveInitialState(new MessageState("gw-2", "systemId", "from", "to", null));
        dlrService.linkOperatorId("gw-1", "op-1");
        dlrService.linkOperatorId("gw-2", "op-2");

        assertEquals(2, dlrService.getCorrelationIndexSize());
    }

    @Test
    void isPersistent_FalseWhenNoDb() {
        assertFalse(dlrService.isPersistent());
    }
}