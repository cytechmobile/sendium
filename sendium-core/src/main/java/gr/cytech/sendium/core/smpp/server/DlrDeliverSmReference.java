package gr.cytech.sendium.core.smpp.server;

import gr.cytech.sendium.core.message.StandardMessage;

/**
 * Typed reference attached to each deliver_sm belonging to a durable DLR batch.
 */
public record DlrDeliverSmReference<M extends StandardMessage>(
        SmppServerSessionHandler<M> handler,
        DlrDeliveryBatch<M> batch,
        int partOrdinal,
        String receiptMessageId) {
}
