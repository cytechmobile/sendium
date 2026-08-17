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

class MvStoreDlrStorageTest {

    private MvStoreDlrStorage storage;
    private Path dbPath;
    private String oldDbPath;

    @BeforeEach
    void setUp() throws Exception {
        oldDbPath = System.getProperty("sendium.dlr.db.path");
        dbPath = Files.createTempFile("dlr-service-test", ".db");
        Files.deleteIfExists(dbPath);
        System.setProperty("sendium.dlr.db.path", dbPath.toString());
        storage = new MvStoreDlrStorage();
        storage.init();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (storage != null) {
            storage.onStop();
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

        storage.saveInitialState(state);

        assertEquals(1, storage.getPrimaryStoreSize());
    }

    @Test
    void saveInitialState_SetsTimestamp() {
        MessageState state = new MessageState("gw-123", "systemId", "from", "to", null);

        storage.saveInitialState(state);

        Optional<MessageState> retrieved = storage.getState("gw-123");
        assertTrue(retrieved.isPresent());
        assertTrue(retrieved.get().getTimestamp() > 0);
    }

    @Test
    void linkOperatorId_LinksCorrelation() {
        MessageState state = new MessageState("gw-123", "systemId", "from", "to", null);
        storage.saveInitialState(state);

        storage.linkOperatorId("gw-123", "op-456");

        assertEquals(1, storage.getCorrelationIndexSize());
    }

    @Test
    void linkOperatorId_UpdatesStatusToSent() {
        MessageState state = new MessageState("gw-123", "systemId", "from", "to", null);
        storage.saveInitialState(state);

        storage.linkOperatorId("gw-123", "op-456");

        Optional<MessageState> retrieved = storage.getState("gw-123");
        assertTrue(retrieved.isPresent());
        assertEquals(MessageState.MessageStatus.SENT, retrieved.get().getStatus());
    }

    @Test
    void resolveAndRemoveDlr_ReturnsAndRemoves() {
        MessageState state = new MessageState("gw-123", "systemId", "from", "to", null);
        storage.saveInitialState(state);
        storage.linkOperatorId("gw-123", "op-456");

        long beforeResolve = System.currentTimeMillis();
        Optional<MessageState> result = storage.resolveAndRemoveDlr(
                "op-456", MessageState.MessageStatus.DELIVERED);

        assertTrue(result.isPresent());
        assertEquals(MessageState.MessageStatus.DELIVERED, result.get().getStatus());
        assertEquals("op-456", result.get().getOperatorMsgId());
        assertTrue(result.get().getTimestamp() >= beforeResolve);
        assertEquals(0, storage.getPrimaryStoreSize());
        assertEquals(0, storage.getCorrelationIndexSize());
    }

    @Test
    void resolveAndRemoveDlr_MissingId_ReturnsEmpty() {
        Optional<MessageState> result = storage.resolveAndRemoveDlr(
                "unknown", MessageState.MessageStatus.DELIVERED);

        assertTrue(result.isEmpty());
    }

    @Test
    void getState_ReturnsWrappedState() {
        MessageState state = new MessageState("gw-123", "systemId", "from", "to", null);
        storage.saveInitialState(state);

        Optional<MessageState> result = storage.getState("gw-123");

        assertTrue(result.isPresent());
        assertEquals("gw-123", result.get().getGatewayMsgId());
    }

    @Test
    void getState_MissingId_ReturnsEmpty() {
        Optional<MessageState> result = storage.getState("unknown");

        assertTrue(result.isEmpty());
    }

    @Test
    void markAsFailed_UpdatesStatusToFailed() {
        MessageState state = new MessageState("gw-123", "systemId", "from", "to", null);
        storage.saveInitialState(state);

        boolean result = storage.markAsFailed("gw-123");

        assertTrue(result);
        Optional<MessageState> updated = storage.getState("gw-123");
        assertTrue(updated.isPresent());
        assertEquals(MessageState.MessageStatus.FAILED, updated.get().getStatus());
    }

    @Test
    void markAsFailed_MissingId_ReturnsFalse() {
        boolean result = storage.markAsFailed("unknown");

        assertFalse(result);
    }

    @Test
    void saveUnpushedDlr_StoresAndReturnsMatchingDlr() {
        StandardMessage dlr = createDlr("account1", "sys1");

        boolean result = storage.saveUnpushedDlr(dlr);
        List<StandardMessage> dlrs = storage.getUnpushedDlrs("sys1");

        assertTrue(result);
        assertTrue(dlrs.stream().anyMatch(msg -> dlr.serial.equals(msg.serial)));
        StandardMessage stored = dlrs.getFirst();
        assertEquals(dlr.state, stored.state);
        assertEquals(dlr.errcode, stored.errcode);
        assertEquals(dlr.acked, stored.acked);
        assertEquals(dlr.priority, stored.priority);
        assertEquals(dlr.reassembledParts, stored.reassembledParts);
        assertEquals(1, storage.getUnpushedDlrIndexSize());
    }

    @Test
    void saveUnpushedDlr_BlankSystemIdReturnsFalse() {
        StandardMessage dlr = createDlr("account1", null);

        boolean result = storage.saveUnpushedDlr(dlr);

        assertFalse(result);
    }

    @Test
    void getUnpushedDlrs_DifferentSystemIdDoesNotMatch() {
        StandardMessage dlr = createDlr("account1", "sys1");
        storage.saveUnpushedDlr(dlr);

        List<StandardMessage> dlrs = storage.getUnpushedDlrs("sys2");

        assertFalse(dlrs.stream().anyMatch(msg -> dlr.serial.equals(msg.serial)));
    }

    @Test
    void getUnpushedDlrs_UsesSystemIdIndex() {
        StandardMessage sys1Dlr = createDlr("account1", "sys1");
        StandardMessage sys2Dlr = createDlr("account2", "sys2");
        storage.saveUnpushedDlr(sys1Dlr);
        storage.saveUnpushedDlr(sys2Dlr);

        List<StandardMessage> dlrs = storage.getUnpushedDlrs("sys1");

        assertEquals(2, storage.getUnpushedDlrIndexSize());
        assertTrue(dlrs.stream().anyMatch(msg -> sys1Dlr.serial.equals(msg.serial)));
        assertFalse(dlrs.stream().anyMatch(msg -> sys2Dlr.serial.equals(msg.serial)));
    }

    @Test
    void removeUnpushedDlr_RemovesStoredDlr() {
        StandardMessage dlr = createDlr("account1", "sys1");
        storage.saveUnpushedDlr(dlr);

        boolean result = storage.removeUnpushedDlr(dlr);
        List<StandardMessage> dlrs = storage.getUnpushedDlrs("sys1");

        assertTrue(result);
        assertFalse(dlrs.stream().anyMatch(msg -> dlr.serial.equals(msg.serial)));
        assertEquals(0, storage.getUnpushedDlrIndexSize());
    }

    @Test
    void claimUnpushedDlrs_HidesClaimedDlrUntilReleased() {
        StandardMessage dlr = createDlr("account1", "sys1");
        storage.saveUnpushedDlr(dlr);

        List<StandardMessage> firstClaim = storage.claimUnpushedDlrs("sys1");
        List<StandardMessage> secondClaim = storage.claimUnpushedDlrs("sys1");
        storage.releaseUnpushedDlrClaim(firstClaim.getFirst());
        List<StandardMessage> afterRelease = storage.claimUnpushedDlrs("sys1");

        assertEquals(1, firstClaim.size());
        assertTrue(secondClaim.isEmpty());
        assertEquals(1, afterRelease.size());
        assertEquals(dlr.serial, afterRelease.getFirst().serial);
    }

    @Test
    void unpushedDlrs_SurviveRestart() throws Exception {
        StandardMessage dlr = createDlr("account-restart", "sys-restart");

        assertTrue(storage.saveUnpushedDlr(dlr));
        storage.onStop();

        storage = new MvStoreDlrStorage();
        storage.init();
        List<StandardMessage> dlrs = storage.getUnpushedDlrs("sys-restart");

        assertTrue(dlrs.stream().anyMatch(msg -> dlr.serial.equals(msg.serial)));
    }

    @Test
    void getPrimaryStoreSize_ReturnsCount() {
        storage.saveInitialState(new MessageState("gw-1", "systemId", "from", "to", null));
        storage.saveInitialState(new MessageState("gw-2", "systemId", "from", "to", null));
        storage.saveInitialState(new MessageState("gw-3", "systemId", "from", "to", null));

        assertEquals(3, storage.getPrimaryStoreSize());
    }

    @Test
    void getCorrelationIndexSize_ReturnsCount() {
        storage.saveInitialState(new MessageState("gw-1", "systemId", "from", "to", null));
        storage.saveInitialState(new MessageState("gw-2", "systemId", "from", "to", null));
        storage.linkOperatorId("gw-1", "op-1");
        storage.linkOperatorId("gw-2", "op-2");

        assertEquals(2, storage.getCorrelationIndexSize());
    }

    @Test
    void isPersistent_TrueWhenDbAvailable() {
        assertTrue(storage.isPersistent());
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
