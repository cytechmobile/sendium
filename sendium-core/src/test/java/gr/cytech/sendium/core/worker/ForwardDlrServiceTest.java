package gr.cytech.sendium.core.worker;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ForwardDlrServiceTest {

    private ForwardDlrService forwardDlrService;

    @BeforeEach
    void setUp() {
        forwardDlrService = new ForwardDlrService();
    }

    @Test
    void mapToKannelType_Accepted_ReturnsBuffered() {
        int result = forwardDlrService.mapToKannelType(MessageState.MessageStatus.ACCEPTED);
        assertEquals(4, result);
    }

    @Test
    void mapToKannelType_Sent_ReturnsSmscSubmit() {
        int result = forwardDlrService.mapToKannelType(MessageState.MessageStatus.SENT);
        assertEquals(8, result);
    }

    @Test
    void mapToKannelType_Delivered_ReturnsSuccess() {
        int result = forwardDlrService.mapToKannelType(MessageState.MessageStatus.DELIVERED);
        assertEquals(1, result);
    }

    @Test
    void mapToKannelType_Failed_ReturnsFailure() {
        int result = forwardDlrService.mapToKannelType(MessageState.MessageStatus.FAILED);
        assertEquals(2, result);
    }

    @Test
    void mapToKannelType_Null_ReturnsBuffered() {
        int result = forwardDlrService.mapToKannelType(null);
        assertEquals(4, result);
    }

    @Test
    void buildForwardUrl_ReplacesDlrTypePlaceholder() {
        String result = forwardDlrService.buildForwardUrl("http://example.com/dlr?type=%d", "msg-123", 1);
        assertEquals("http://example.com/dlr?type=1", result);
    }

    @Test
    void buildForwardUrl_ReplacesMsgIdPlaceholder() {
        String result = forwardDlrService.buildForwardUrl("http://example.com/dlr?id=%s", "msg-123", 1);
        assertEquals("http://example.com/dlr?id=msg-123", result);
    }

    @Test
    void buildForwardUrl_BothPlaceholders() {
        String result = forwardDlrService.buildForwardUrl("http://example.com/dlr?id=%s&type=%d", "msg-123", 2);
        assertEquals("http://example.com/dlr?id=msg-123&type=2", result);
    }

    @Test
    void buildForwardUrl_NoPlaceholders_Unchanged() {
        String result = forwardDlrService.buildForwardUrl("http://example.com/dlr?id=123", "msg-123", 1);
        assertEquals("http://example.com/dlr?id=123", result);
    }

    @Test
    void forwardDlr_NullUrl_NoAction() {
        MessageState state = new MessageState("msg-1", "system", "from", "to", null);
        assertDoesNotThrow(() -> forwardDlrService.forwardDlr(state));
    }

    @Test
    void forwardDlr_EmptyUrl_NoAction() {
        MessageState state = new MessageState("msg-1", "system", "from", "to", "");
        assertDoesNotThrow(() -> forwardDlrService.forwardDlr(state));
    }

    @Test
    void forwardDlr_WhitespaceUrl_NoAction() {
        MessageState state = new MessageState("msg-1", "system", "from", "to", "   ");
        assertDoesNotThrow(() -> forwardDlrService.forwardDlr(state));
    }
}