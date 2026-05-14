package gr.cytech.sendium.core.worker;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ForwardMoServiceTest {

    private ForwardMoService forwardMoService;

    @BeforeEach
    void setUp() {
        forwardMoService = new ForwardMoService();
    }

    @Test
    void buildForwardUrl_AllPlaceholders() {
        var ctx = new ForwardMoService.MoContext("fromVal", "toVal", "textVal", "tsVal", "igwVal", "mcVal", (byte) 5);
        String result = forwardMoService.buildForwardUrl(
                "http://example.com/mo?from=%p&to=%P&text=%a&ts=%t&igw=%i&mc=%I&coding=%o", ctx);
        assertEquals("http://example.com/mo?from=fromVal&to=toVal&text=textVal&ts=tsVal&igw=igwVal&mc=mcVal&coding=5", result);
    }

    @Test
    void buildForwardUrl_NoPlaceholders_Unchanged() {
        var ctx = new ForwardMoService.MoContext("fromVal", "toVal", "textVal", "tsVal", "igwVal", "mcVal", (byte) 0);
        String result = forwardMoService.buildForwardUrl("http://example.com/mo?id=123", ctx);
        assertEquals("http://example.com/mo?id=123", result);
    }

    @Test
    void buildForwardUrl_NullText_ReplacesWithEmpty() {
        var ctx = new ForwardMoService.MoContext("fromVal", "toVal", null, "tsVal", "igwVal", "mcVal", (byte) 0);
        String result = forwardMoService.buildForwardUrl("http://example.com/mo?text=%a", ctx);
        assertEquals("http://example.com/mo?text=", result);
    }

    @Test
    void buildForwardUrl_NullTimestamp_ReplacesWithEmpty() {
        var ctx = new ForwardMoService.MoContext("fromVal", "toVal", "text", null, "igwVal", "mcVal", (byte) 0);
        String result = forwardMoService.buildForwardUrl("http://example.com/mo?ts=%t", ctx);
        assertEquals("http://example.com/mo?ts=", result);
    }

    @Test
    void buildFormBody_AllFieldsPresent() {
        var ctx = new ForwardMoService.MoContext("12345", "99999", "hello world", "ts", "ingw", "mc", (byte) 0);
        String body = forwardMoService.buildFormBody(ctx);
        assertTrue(body.contains("from=12345"));
        assertTrue(body.contains("to=99999"));
        assertTrue(body.contains("text=hello+world"));
        assertTrue(body.contains("timestamp=ts"));
        assertTrue(body.contains("ingateway=ingw"));
        assertTrue(body.contains("message_center=mc"));
        assertTrue(body.contains("coding=0"));
    }

    @Test
    void buildFormBody_NullFields_Skipped() {
        var ctx = new ForwardMoService.MoContext("12345", null, "hello", "ts", null, null, (byte) 0);
        String body = forwardMoService.buildFormBody(ctx);
        assertTrue(body.contains("from=12345"));
        assertTrue(body.contains("text=hello"));
        assertTrue(body.contains("timestamp=ts"));
        assertTrue(body.contains("coding=0"));
    }

    @Test
    void forwardMo_NullUrl_NoAction() {
        var ctx = new ForwardMoService.MoContext("from", "to", "text", "ts", "igw", "mc", (byte) 0);
        assertDoesNotThrow(() -> forwardMoService.forwardMo(null, ctx));
    }

    @Test
    void forwardMo_EmptyUrl_NoAction() {
        var ctx = new ForwardMoService.MoContext("from", "to", "text", "ts", "igw", "mc", (byte) 0);
        assertDoesNotThrow(() -> forwardMoService.forwardMo("", ctx));
    }

    @Test
    void forwardMo_NullUrlWithFormat_NoAction() {
        var ctx = new ForwardMoService.MoContext("from", "to", "text", "ts", "igw", "mc", (byte) 0);
        assertDoesNotThrow(() -> forwardMoService.forwardMo(null, ctx, ForwardMoService.ForwardFormat.JSON));
    }

    @Test
    void buildJsonBody_AllFields() {
        var ctx = new ForwardMoService.MoContext("12345", "99999", "hello", "ts", "ingw", "mc", (byte) 3);
        String json = forwardMoService.buildJsonBody(ctx);
        assertTrue(json.contains("\"from\":\"12345\""));
        assertTrue(json.contains("\"to\":\"99999\""));
        assertTrue(json.contains("\"text\":\"hello\""));
        assertTrue(json.contains("\"timestamp\":\"ts\""));
        assertTrue(json.contains("\"ingateway\":\"ingw\""));
        assertTrue(json.contains("\"messageCenter\":\"mc\""));
        assertTrue(json.contains("\"dataCoding\":3"));
    }

    @Test
    void buildJsonBody_NullFields() {
        var ctx = new ForwardMoService.MoContext("12345", null, null, "ts", null, "mc", (byte) 0);
        String json = forwardMoService.buildJsonBody(ctx);
        assertTrue(json.contains("\"from\":\"12345\""));
        assertTrue(json.contains("\"timestamp\":\"ts\""));
        assertTrue(json.contains("\"messageCenter\":\"mc\""));
        assertTrue(json.contains("\"dataCoding\":0"));
    }
}
