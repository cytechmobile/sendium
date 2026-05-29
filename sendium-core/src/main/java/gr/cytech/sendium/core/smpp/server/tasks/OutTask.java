package gr.cytech.sendium.core.smpp.server.tasks;

import com.cloudhopper.smpp.pdu.Pdu;
import com.cloudhopper.smpp.pdu.PduRequest;
import com.cloudhopper.smpp.pdu.PduResponse;
import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.smpp.server.SmppServerSessionHandler;
import gr.cytech.sendium.core.smpp.server.SmppServerWorker;
import gr.cytech.sendium.util.MessageTrace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OutTask<M extends StandardMessage> implements Runnable {
    private static final Logger logger = LoggerFactory.getLogger(OutTask.class);
    private final SmppServerWorker<M> worker;
    private final Pdu pdu;
    private M msg;

    public OutTask(SmppServerWorker<M> worker, Pdu pdu) {
        this.worker = worker;
        this.pdu = pdu;
    }

    @Override
    public void run() {
        boolean success;
        try {
            if (pdu.isResponse()) {
                //for responses, the pdu contains the handler as a reference object
                msg = null;
                Object ref = pdu.getReferenceObject();
                SmppServerSessionHandler handler;
                if (ref instanceof Object[]) {
                    Object[] arr = (Object[]) pdu.getReferenceObject();
                    handler = (SmppServerSessionHandler) arr[0];
                } else {
                    handler = (SmppServerSessionHandler) ref;
                }
                success = handler.sendPduResponse((PduResponse) pdu);
            } else {
                //for requests, the pdu contains an array with the handler and possibly the original message (dlr/mo)
                Object[] arr = (Object[]) pdu.getReferenceObject();
                SmppServerSessionHandler handler = (SmppServerSessionHandler) arr[0];
                msg = (M) arr[1];
                success = handler.sendPduRequest((PduRequest) pdu);
            }
        } catch (Exception e) {
            success = false;
            logger.warn("Exception at send pdu for worker {}", worker.getFullName(), e);
        }

        if (!success) {
            worker.outTaskFailed(pdu, msg);
        } else if (!pdu.isResponse() && msg != null) {
            if (MessageTrace.shouldLog(worker.getConfigurationProvider(), MessageTrace.EVENT_DELIVER_SENT)) {
                logger.info("message.deliver.sent worker={} {}", worker.getFullName(), MessageTrace.identifiers(msg));
            }
        }
    }

    public Pdu getPdu() {
        return pdu;
    }
}
