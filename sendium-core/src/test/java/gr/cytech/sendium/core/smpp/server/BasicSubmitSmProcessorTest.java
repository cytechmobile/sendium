package gr.cytech.sendium.core.smpp.server;

import com.cloudhopper.smpp.SmppConstants;
import com.cloudhopper.smpp.pdu.SubmitSm;
import com.cloudhopper.smpp.type.Address;
import com.cloudhopper.smpp.type.SmppProcessingException;
import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.smpp.util.SmppServerUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BasicSubmitSmProcessorTest {

    private SmppServerWorker<StandardMessage> worker;
    private BasicSubmitSmProcessor<StandardMessage> processor;
    private SmppSessionContext context;
    private SmppServerUtil.ValidatedMessageBody bodyInfo;

    @BeforeEach
    void setUp() {
        worker = mock(SmppServerWorker.class);
        processor = new BasicSubmitSmProcessor<>(worker);
        context = mock(SmppSessionContext.class);
        bodyInfo = mock(SmppServerUtil.ValidatedMessageBody.class);

        when(worker.getPtrnValidReceiver()).thenReturn("[+]?[0-9]{10,20}");
        when(context.getAccountId()).thenReturn("testSystemId");
        when(context.getSystemId()).thenReturn("testSystemId");
    }

    @Test
    void processSubmitSm_Success() throws Exception {
        SubmitSm submitSm = new SubmitSm();
        submitSm.setSourceAddress(new Address((byte) 0, (byte) 0, "sender"));
        submitSm.setDestAddress(new Address((byte) 0, (byte) 0, "306984443255"));
        submitSm.setRegisteredDelivery(SmppConstants.REGISTERED_DELIVERY_SMSC_RECEIPT_REQUESTED);
        submitSm.setPriority((byte) StandardMessage.HIGH_PRIORITY);
        submitSm.setDataCoding((byte) 0x08);

        when(bodyInfo.text()).thenReturn("test message");
        when(bodyInfo.smType()).thenReturn(StandardMessage.MSG_UCS2);
        when(bodyInfo.udh()).thenReturn("050003010201");

        Timestamp scheduleDeliveryTime = new Timestamp(System.currentTimeMillis());

        InEvent<StandardMessage> event = processor.processSubmitSm(submitSm, context, bodyInfo, scheduleDeliveryTime);

        assertNotNull(event);
        StandardMessage msg = event.pMsg;
        assertEquals("sender", msg.from);
        assertEquals("306984443255", msg.to);
        assertEquals("test message", msg.body);
        assertEquals(StandardMessage.MSG_UCS2, msg.type);
        assertEquals("050003010201", msg.binheader);
        assertEquals("testSystemId", msg.systemId);
        assertTrue(msg.acked);
        assertEquals(StandardMessage.HIGH_PRIORITY, msg.priority);
        assertEquals(scheduleDeliveryTime.toString(), msg.timestamp);
        assertEquals((byte) 0x08, msg.dcs);
    }

    @Test
    void processSubmitSm_MissingSourceAddress() {
        SubmitSm submitSm = new SubmitSm();
        submitSm.setDestAddress(new Address((byte) 0, (byte) 0, "306912345678"));

        SmppProcessingException ex = assertThrows(SmppProcessingException.class, () ->
                processor.processSubmitSm(submitSm, context, bodyInfo, null));
        assertEquals(SmppConstants.STATUS_INVSRCADR, ex.getErrorCode());
    }

    @Test
    void processSubmitSm_MissingDestAddress() {
        SubmitSm submitSm = new SubmitSm();
        submitSm.setSourceAddress(new Address((byte) 0, (byte) 0, "sender"));

        SmppProcessingException ex = assertThrows(SmppProcessingException.class, () ->
                processor.processSubmitSm(submitSm, context, bodyInfo, null));
        assertEquals(SmppConstants.STATUS_INVDSTADR, ex.getErrorCode());
    }

    @Test
    void processSubmitSm_InvalidDestAddress() {
        SubmitSm submitSm = new SubmitSm();
        submitSm.setSourceAddress(new Address((byte) 0, (byte) 0, "sender"));
        submitSm.setDestAddress(new Address((byte) 0, (byte) 0, "invalid"));

        SmppProcessingException ex = assertThrows(SmppProcessingException.class, () ->
                processor.processSubmitSm(submitSm, context, bodyInfo, null));
        assertEquals(SmppConstants.STATUS_INVDSTADR, ex.getErrorCode());
    }

    @Test
    void processSubmitSm_WithoutRegisteredDelivery() throws Exception {
        SubmitSm submitSm = new SubmitSm();
        submitSm.setSourceAddress(new Address((byte) 0, (byte) 0, "sender"));
        submitSm.setDestAddress(new Address((byte) 0, (byte) 0, "306984443255"));
        submitSm.setRegisteredDelivery(SmppConstants.REGISTERED_DELIVERY_SMSC_RECEIPT_NOT_REQUESTED);

        when(bodyInfo.text()).thenReturn("test message");
        when(bodyInfo.smType()).thenReturn(StandardMessage.MSG_TEXT);
        when(bodyInfo.udh()).thenReturn(null);

        InEvent<StandardMessage> event = processor.processSubmitSm(submitSm, context, bodyInfo, null);

        assertNotNull(event);
        StandardMessage msg = event.pMsg;
        assertFalse(msg.acked);
    }

    @Test
    void processSubmitSm_DefaultPriority_WhenOutOfBounds() throws Exception {
        SubmitSm submitSm = new SubmitSm();
        submitSm.setSourceAddress(new Address((byte) 0, (byte) 0, "sender"));
        submitSm.setDestAddress(new Address((byte) 0, (byte) 0, "306984443255"));
        submitSm.setPriority((byte) 10);

        when(bodyInfo.text()).thenReturn("test message");
        when(bodyInfo.smType()).thenReturn(StandardMessage.MSG_TEXT);
        when(bodyInfo.udh()).thenReturn(null);

        InEvent<StandardMessage> event = processor.processSubmitSm(submitSm, context, bodyInfo, null);

        assertNotNull(event);
        assertEquals(StandardMessage.NORMAL_PRIORITY, event.pMsg.priority);
    }

    @Test
    void processSubmitSm_WithoutScheduleDeliveryTime() throws Exception {
        SubmitSm submitSm = new SubmitSm();
        submitSm.setSourceAddress(new Address((byte) 0, (byte) 0, "sender"));
        submitSm.setDestAddress(new Address((byte) 0, (byte) 0, "306984443255"));
        submitSm.setRegisteredDelivery(SmppConstants.REGISTERED_DELIVERY_SMSC_RECEIPT_NOT_REQUESTED);

        when(bodyInfo.text()).thenReturn("test message");
        when(bodyInfo.smType()).thenReturn(StandardMessage.MSG_TEXT);
        when(bodyInfo.udh()).thenReturn(null);

        InEvent<StandardMessage> event = processor.processSubmitSm(submitSm, context, bodyInfo, null);

        assertNotNull(event);
        assertEquals("", event.pMsg.timestamp);
    }
}
