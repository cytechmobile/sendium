package gr.cytech.sendium.core.smpp.server;

import com.cloudhopper.smpp.SmppConstants;
import com.cloudhopper.smpp.pdu.SubmitSm;
import com.cloudhopper.smpp.type.SmppProcessingException;
import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.smpp.util.SmppServerUtil;

import java.sql.Timestamp;
import java.util.regex.Pattern;

public class BasicSubmitSmProcessor<M extends StandardMessage> implements SubmitSmProcessor<StandardMessage> {
    private final SmppServerWorker<M> worker;

    public BasicSubmitSmProcessor(SmppServerWorker<M> worker) {
        this.worker = worker;
    }

    @Override
    public InEvent<StandardMessage> processSubmitSm(SubmitSm submitSm, SmppSessionContext context,
                                                    SmppServerUtil.ValidatedMessageBody bodyInfo,
                                                    Timestamp scheduleDeliveryTime) throws SmppProcessingException {
        if (submitSm.getSourceAddress() == null) {
            throw new SmppProcessingException(SmppConstants.STATUS_INVSRCADR, null);
        }
        if (submitSm.getDestAddress() == null) {
            throw new SmppProcessingException(SmppConstants.STATUS_INVDSTADR, null);
        }

        String msisdn = submitSm.getDestAddress().getAddress();
        if (!Pattern.matches(worker.getPtrnValidReceiver(), msisdn)) {
            throw new SmppProcessingException(SmppConstants.STATUS_INVDSTADR, null);
        }

        String originator = submitSm.getSourceAddress().getAddress();

        StandardMessage pMsg = new StandardMessage();
        pMsg.from = originator;
        pMsg.to = msisdn;
        pMsg.body = bodyInfo.text();
        pMsg.type = bodyInfo.smType();
        pMsg.binheader = bodyInfo.udh();
        pMsg.owner_id = context.getAccountId();
        pMsg.systemId = context.getSystemId();
        pMsg.acked = submitSm.getRegisteredDelivery() != SmppConstants.REGISTERED_DELIVERY_SMSC_RECEIPT_NOT_REQUESTED;
        pMsg.priority = (submitSm.getPriority() >= StandardMessage.LOW_PRIORITY && submitSm.getPriority() <= StandardMessage.HIGH_PRIORITY) ?
                submitSm.getPriority() : StandardMessage.NORMAL_PRIORITY;
        if (scheduleDeliveryTime != null) {
            pMsg.timestamp = scheduleDeliveryTime.toString();
        }
        pMsg.dcs = submitSm.getDataCoding();

        return new InEvent<>(pMsg, submitSm, 0, scheduleDeliveryTime);
    }
}
