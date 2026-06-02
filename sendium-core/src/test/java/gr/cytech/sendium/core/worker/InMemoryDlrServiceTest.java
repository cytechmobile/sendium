package gr.cytech.sendium.core.worker;

import gr.cytech.sendium.core.message.StandardMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryDlrServiceTest {

    private InMemoryDlrService dlrService;
    private Path dbPath;
    private String oldDbPath;

    @BeforeEach
    void setUp() throws Exception {
        oldDbPath = System.getProperty("sendium.dlr.db.path");
        dbPath = Files.createTempFile("dlr-service-test", ".db");
        Files.deleteIfExists(dbPath);
        System.setProperty("sendium.dlr.db.path", dbPath.toString());
        dlrService = new InMemoryDlrService();
        dlrService.init();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (dlrService != null) {
            dlrService.onStop();
        }
        if (oldDbPath == null) {
            System.clearProperty("sendium.dlr.db.path");
        } else {
            System.setProperty("sendium.dlr.db.path", oldDbPath);
        }
        if (dbPath != null) {
            Files.deleteIfExists(dbPath);
        }
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
    void saveUnpushedDlr_StoresAndReturnsMatchingDlr() {
        StandardMessage dlr = createDlr("account1", "sys1");

        boolean result = dlrService.saveUnpushedDlr(dlr);
        List<StandardMessage> dlrs = dlrService.getUnpushedDlrs("sys1");

        assertTrue(result);
        assertTrue(dlrs.stream().anyMatch(msg -> dlr.serial.equals(msg.serial)));
        StandardMessage stored = dlrs.getFirst();
        assertEquals(dlr.state, stored.state);
        assertEquals(dlr.errcode, stored.errcode);
        assertEquals(dlr.acked, stored.acked);
        assertEquals(dlr.priority, stored.priority);
        assertEquals(dlr.reassembledParts, stored.reassembledParts);
        assertEquals(1, dlrService.getUnpushedDlrIndexSize());
    }

    @Test
    void saveUnpushedDlr_BlankSystemIdReturnsFalse() {
        StandardMessage dlr = createDlr("account1", null);

        boolean result = dlrService.saveUnpushedDlr(dlr);

        assertFalse(result);
    }

    @Test
    void getUnpushedDlrs_DifferentSystemIdDoesNotMatch() {
        StandardMessage dlr = createDlr("account1", "sys1");
        dlrService.saveUnpushedDlr(dlr);

        List<StandardMessage> dlrs = dlrService.getUnpushedDlrs("sys2");

        assertFalse(dlrs.stream().anyMatch(msg -> dlr.serial.equals(msg.serial)));
    }

    @Test
    void getUnpushedDlrs_UsesSystemIdIndex() {
        StandardMessage sys1Dlr = createDlr("account1", "sys1");
        StandardMessage sys2Dlr = createDlr("account2", "sys2");
        dlrService.saveUnpushedDlr(sys1Dlr);
        dlrService.saveUnpushedDlr(sys2Dlr);

        List<StandardMessage> dlrs = dlrService.getUnpushedDlrs("sys1");

        assertEquals(2, dlrService.getUnpushedDlrIndexSize());
        assertTrue(dlrs.stream().anyMatch(msg -> sys1Dlr.serial.equals(msg.serial)));
        assertFalse(dlrs.stream().anyMatch(msg -> sys2Dlr.serial.equals(msg.serial)));
    }

    @Test
    void removeUnpushedDlr_RemovesStoredDlr() {
        StandardMessage dlr = createDlr("account1", "sys1");
        dlrService.saveUnpushedDlr(dlr);

        boolean result = dlrService.removeUnpushedDlr(dlr);
        List<StandardMessage> dlrs = dlrService.getUnpushedDlrs("sys1");

        assertTrue(result);
        assertFalse(dlrs.stream().anyMatch(msg -> dlr.serial.equals(msg.serial)));
        assertEquals(0, dlrService.getUnpushedDlrIndexSize());
    }

    @Test
    void claimUnpushedDlrs_HidesClaimedDlrUntilReleased() {
        StandardMessage dlr = createDlr("account1", "sys1");
        dlrService.saveUnpushedDlr(dlr);

        List<StandardMessage> firstClaim = dlrService.claimUnpushedDlrs("sys1");
        List<StandardMessage> secondClaim = dlrService.claimUnpushedDlrs("sys1");
        dlrService.releaseUnpushedDlrClaim(firstClaim.getFirst());
        List<StandardMessage> afterRelease = dlrService.claimUnpushedDlrs("sys1");

        assertEquals(1, firstClaim.size());
        assertTrue(secondClaim.isEmpty());
        assertEquals(1, afterRelease.size());
        assertEquals(dlr.serial, afterRelease.getFirst().serial);
    }

    @Test
    void unpushedDlrs_SurviveRestart() throws Exception {
        StandardMessage dlr = createDlr("account-restart", "sys-restart");

        assertTrue(dlrService.saveUnpushedDlr(dlr));
        dlrService.onStop();

        dlrService = new InMemoryDlrService();
        dlrService.init();
        List<StandardMessage> dlrs = dlrService.getUnpushedDlrs("sys-restart");

        assertTrue(dlrs.stream().anyMatch(msg -> dlr.serial.equals(msg.serial)));
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
    void isPersistent_TrueWhenDbAvailable() {
        assertTrue(dlrService.isPersistent());
    }

    private StandardMessage createDlr(String accountId, String systemId) {
        StandardMessage dlr = new StandardMessage();
        dlr.type = StandardMessage.MSG_DLR;
        dlr.owner_id = accountId;
        dlr.systemId = systemId;
        dlr.serial = UUID.randomUUID().toString();
        dlr.from = "from";
        dlr.to = "to";
        dlr.state = StandardMessage.DLR_STAT_DELIVRD;
        dlr.errcode = "0";
        dlr.acked = true;
        dlr.priority = StandardMessage.HIGH_PRIORITY;
        dlr.msgId = 123;
        dlr.reassembledParts = new ArrayList<>(List.of("part-1", "part-2"));
        return dlr;
    }
}
