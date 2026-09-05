package gr.cytech.sendium.core.smpp.server.tasks;

import com.cloudhopper.smpp.pdu.DeliverSm;
import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.smpp.server.DlrDeliverSmReference;
import gr.cytech.sendium.core.smpp.server.DlrDeliveryBatch;
import gr.cytech.sendium.core.smpp.server.SmppServerMessageStore;
import gr.cytech.sendium.core.smpp.server.SmppServerSessionHandler;
import gr.cytech.sendium.core.smpp.server.SmppServerWorker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutTaskTest {
    @Mock private SmppServerWorker<StandardMessage> worker;
    @Mock private SmppServerSessionHandler<StandardMessage> handler;
    @Mock private SmppServerMessageStore<StandardMessage> store;

    private StandardMessage message;

    @BeforeEach
    void setUp() {
        message = new StandardMessage();
        message.serial = "gateway-1";
        message.type = StandardMessage.MSG_DLR;
    }

    @Test
    void successfulSendOnlyDispatchesAndDoesNotCompleteBatch() throws Exception {
        DlrDeliveryBatch<StandardMessage> batch = batch(1);
        DeliverSm request = request(batch);
        when(handler.sendPduRequest(request)).thenReturn(true);

        new OutTask<>(worker, request).run();

        verify(handler).sendPduRequest(request);
        verify(store, never()).completeDlrDeliveryAttempt(any(), eq(1));
        verify(store, never()).releaseDlrDeliveryAttempt(any(), eq(1), any());
    }

    @Test
    void failedSendReleasesBatchWithoutLegacyWorkerFailure() throws Exception {
        DlrDeliveryBatch<StandardMessage> batch = batch(2);
        DeliverSm request = request(batch);
        when(handler.sendPduRequest(request)).thenReturn(false);
        when(store.releaseDlrDeliveryAttempt(message, 2, "send_failed")).thenReturn(true);

        new OutTask<>(worker, request).run();

        verify(store).releaseDlrDeliveryAttempt(message, 2, "send_failed");
        verify(worker, never()).outTaskFailed(any(), any());
    }

    @Test
    void inactiveBatchIsNotSent() throws Exception {
        DlrDeliveryBatch<StandardMessage> batch = batch(3);
        when(store.releaseDlrDeliveryAttempt(message, 3, "timeout")).thenReturn(true);
        batch.fail("timeout");
        DeliverSm request = request(batch);

        new OutTask<>(worker, request).run();

        verify(handler, never()).sendPduRequest(any());
    }

    private DlrDeliveryBatch<StandardMessage> batch(int attempt) {
        return new DlrDeliveryBatch<>(message, attempt, Set.of(0), store, handler);
    }

    private DeliverSm request(DlrDeliveryBatch<StandardMessage> batch) {
        DeliverSm request = new DeliverSm();
        request.setReferenceObject(new DlrDeliverSmReference<>(handler, batch, 0, "receipt-1"));
        return request;
    }
}
