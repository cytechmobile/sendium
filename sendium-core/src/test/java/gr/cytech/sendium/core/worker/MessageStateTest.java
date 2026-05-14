package gr.cytech.sendium.core.worker;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MessageStateTest {

    @Test
    void constructor_SetsAllFields() {
        MessageState state = new MessageState("gw-123", "systemId", "from", "to", null);

        assertEquals("gw-123", state.getGatewayMsgId());
        assertEquals("systemId", state.getSystemId());
        assertEquals("from", state.getSourceAddr());
        assertEquals("to", state.getDestAddr());
    }

    @Test
    void constructor_DefaultsStatusToAccepted() {
        MessageState state = new MessageState("gw-123", "systemId", "from", "to", null);

        assertEquals(MessageState.MessageStatus.ACCEPTED, state.getStatus());
    }

    @Test
    void constructor_SetsTimestampToCurrentTime() {
        long before = System.currentTimeMillis();
        MessageState state = new MessageState("gw-123", "systemId", "from", "to", null);
        long after = System.currentTimeMillis();

        assertTrue(state.getTimestamp() >= before);
        assertTrue(state.getTimestamp() <= after);
    }

    @Test
    void getOperatorMsgId_InitiallyNull() {
        MessageState state = new MessageState("gw-123", "systemId", "from", "to", null);

        assertNull(state.getOperatorMsgId());
    }

    @Test
    void setOperatorMsgId_UpdatesValue() {
        MessageState state = new MessageState("gw-123", "systemId", "from", "to", null);

        state.setOperatorMsgId("op-456");

        assertEquals("op-456", state.getOperatorMsgId());
    }

    @Test
    void setStatus_UpdatesStatus() {
        MessageState state = new MessageState("gw-123", "systemId", "from", "to", null);

        state.setStatus(MessageState.MessageStatus.SENT);

        assertEquals(MessageState.MessageStatus.SENT, state.getStatus());
    }

    @Test
    void setTimestamp_UpdatesTimestamp() {
        MessageState state = new MessageState("gw-123", "systemId", "from", "to", null);
        long newTimestamp = 1234567890000L;

        state.setTimestamp(newTimestamp);

        assertEquals(newTimestamp, state.getTimestamp());
    }
}