package gr.cytech.sendium.core.smpp.server;

import com.cloudhopper.smpp.pdu.SubmitSm;
import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.outbound.OutboundWork.SourceId;

import java.sql.Timestamp;
import java.util.Set;

public class InEvent<M extends StandardMessage> {
    public final M pMsg;
    public final SubmitSm submitSm;
    public final int mpid;
    public final Timestamp localTimestamp;
    public final Set<SourceId> sourceIds;
    public boolean notifyClient;
    public boolean concatenated;
    public boolean waitingForResponse;
    public String responseMessageId;

    public InEvent(M pMsg, SubmitSm submitSm, int mpid, Timestamp localTimestamp) {
        this(pMsg, submitSm, mpid, localTimestamp, true, null);
    }

    public InEvent(M pMsg, SubmitSm submitSm, int mpid, Timestamp localTimestamp, boolean waitingForResponse, String messageId) {
        this(pMsg, submitSm, mpid, localTimestamp, waitingForResponse, messageId, Set.of());
    }

    /** Coordinated events retain source IDs; an event without submitSm publishes already-accepted ready work. */
    public InEvent(M pMsg, SubmitSm submitSm, int mpid, Timestamp localTimestamp, boolean waitingForResponse,
                   String messageId, Set<SourceId> sourceIds) {
        this.pMsg = pMsg;
        this.submitSm = submitSm;
        this.mpid = mpid;
        this.localTimestamp = localTimestamp;
        this.sourceIds = Set.copyOf(sourceIds);
        this.notifyClient = true;
        this.concatenated = false;
        this.waitingForResponse = waitingForResponse;
        this.responseMessageId = messageId;
    }

    public String toString() {
        return "InEvent{" +
                "pMsg=" + pMsg +
                ", submitSm=" + submitSm +
                ", mpid=" + mpid +
                ", localTimestamp=" + localTimestamp +
                ", udh=" + (pMsg == null ? "" : pMsg.binheader) +
                ", notifyClient=" + notifyClient +
                ", concatenated=" + concatenated +
                ", waitingForResponse=" + waitingForResponse +
                ", responseMessageId=" + responseMessageId +
                '}';
    }
}
