package gr.cytech.sendium.core.smpp.server;

import com.cloudhopper.smpp.pdu.SubmitSm;
import com.cloudhopper.smpp.type.SmppProcessingException;
import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.smpp.util.SmppServerUtil;

import java.sql.Timestamp;

public interface SubmitSmProcessor<M extends StandardMessage> {
    InEvent<M> processSubmitSm(
            SubmitSm submitSm,
            SmppSessionContext context,
            SmppServerUtil.ValidatedMessageBody bodyInfo,
            Timestamp scheduleDeliveryTime
    ) throws SmppProcessingException;
}
