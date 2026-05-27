package gr.cytech.sendium.core.smpp.client;

import com.cloudhopper.smpp.PduAsyncResponse;
import com.cloudhopper.smpp.SmppConstants;
import com.cloudhopper.smpp.pdu.DeliverSm;
import com.cloudhopper.smpp.pdu.EnquireLink;
import com.cloudhopper.smpp.pdu.PduResponse;
import com.cloudhopper.smpp.pdu.QuerySm;
import com.cloudhopper.smpp.pdu.SubmitSm;
import com.cloudhopper.smpp.pdu.SubmitSmResp;
import gr.cytech.sendium.core.message.StandardMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SmppClientSessionHandlerTest {

    @Mock private SmppClientWorker<StandardMessage> worker;

    private SmppClientSessionHandler handler;

    private SmppClientWorker.ConnectionInfo connectionInfo;

    private DeliverSm moResponseSource;
    private DeliverSm dlrResponseSource;

    @BeforeEach
    void setUp() {
        when(worker.getFullName()).thenReturn("smppclient.test");
        connectionInfo = new SmppClientWorker.ConnectionInfo(null, "localhost", 2775,
                SmppClientWorker.ConnectionType.NORMAL);
        handler = new SmppClientSessionHandler(worker, connectionInfo);
        moResponseSource = new DeliverSm();
        dlrResponseSource = new DeliverSm();
    }

    @Test
    void firePduRequestReceived_whenDeliverSmIsDeliveryReceipt_delegatesToDlrParser() {
        DeliverSm deliverSm = new DeliverSm();
        deliverSm.setEsmClass(SmppConstants.ESM_CLASS_MT_SMSC_DELIVERY_RECEIPT);
        PduResponse expected = dlrResponseSource.createResponse();
        when(worker.parseDlrAndCreateResponse(deliverSm)).thenReturn(expected);

        PduResponse response = handler.firePduRequestReceived(deliverSm);

        assertThat(response).isSameAs(expected);
        assertThat(response.getReferenceObject()).isSameAs(handler);
        verify(worker, never()).parseMoAndCreateResponse(any());
    }

    @Test
    void firePduRequestReceived_whenDeliverSmIsMo_delegatesToMoParser() {
        DeliverSm deliverSm = new DeliverSm();
        PduResponse expected = moResponseSource.createResponse();
        when(worker.parseMoAndCreateResponse(deliverSm)).thenReturn(expected);

        PduResponse response = handler.firePduRequestReceived(deliverSm);

        assertThat(response).isSameAs(expected);
        assertThat(response.getReferenceObject()).isSameAs(handler);
        verify(worker, never()).parseDlrAndCreateResponse(any());
    }

    @Test
    void firePduRequestReceived_whenSubmitSmIsReceived_treatsItAsMo() {
        SubmitSm submitSm = new SubmitSm();
        PduResponse expected = submitSm.createResponse();
        when(worker.parseMoAndCreateResponse(submitSm)).thenReturn(expected);

        PduResponse response = handler.firePduRequestReceived(submitSm);

        assertThat(response).isSameAs(expected);
        verify(worker).parseMoAndCreateResponse(submitSm);
    }

    @Test
    void firePduRequestReceived_whenUnsupportedCommand_returnsInvalidCommandNack() {
        QuerySm querySm = new QuerySm();

        PduResponse response = handler.firePduRequestReceived(querySm);

        assertThat(response.getCommandStatus()).isEqualTo(SmppConstants.STATUS_INVCMDID);
    }

    @Test
    void fireExpectedPduResponseReceived_whenSubmitResponseHasAttachedMessage_delegatesToWorker() {
        StandardMessage msg = new StandardMessage();
        SubmitSm submitSm = new SubmitSm();
        submitSm.setReferenceObject(msg);
        SubmitSmResp submitSmResp = new SubmitSmResp();
        submitSmResp.setCommandStatus(SmppConstants.STATUS_OK);
        submitSmResp.setMessageId("smsc-1");
        PduAsyncResponse asyncResponse = mock(PduAsyncResponse.class);
        when(asyncResponse.getRequest()).thenReturn(submitSm);
        when(asyncResponse.getResponse()).thenReturn(submitSmResp);

        handler.fireExpectedPduResponseReceived(asyncResponse);

        verify(worker).handleResponse(handler, SmppConstants.STATUS_OK, "smsc-1", msg);
    }

    @Test
    void fireExpectedPduResponseReceived_whenSubmitResponseHasNoMessage_ignoresResponse() {
        SubmitSm submitSm = new SubmitSm();
        SubmitSmResp submitSmResp = new SubmitSmResp();
        PduAsyncResponse asyncResponse = mock(PduAsyncResponse.class);
        when(asyncResponse.getRequest()).thenReturn(submitSm);
        when(asyncResponse.getResponse()).thenReturn(submitSmResp);

        handler.fireExpectedPduResponseReceived(asyncResponse);

        verify(worker, never()).handleResponse(eq(handler), any(Integer.class), any(), any());
    }

    @Test
    void firePduRequestExpired_whenSubmitHasAttachedMessage_marksDeliveryFailure() {
        StandardMessage msg = new StandardMessage();
        SubmitSm submitSm = new SubmitSm();
        submitSm.setReferenceObject(msg);

        handler.firePduRequestExpired(submitSm);

        verify(worker).handleResponse(handler, SmppConstants.STATUS_DELIVERYFAILURE, null, msg);
    }

    @Test
    void firePduRequestExpired_whenEnquireLinkExpiresAfterThreshold_removesConnection() {
        when(worker.getMaxConsecutiveFailedEnquireLinksBeforeReconnecting()).thenReturn(1);

        handler.firePduRequestExpired(new EnquireLink());

        verify(worker).removeConnection(handler, true, true);
    }
}
