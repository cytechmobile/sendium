package gr.cytech.sendium.core.worker;

import gr.cytech.sendium.core.message.StandardMessage;

import java.util.List;
import java.util.Optional;

/**
 * Persistence boundary for delivery-receipt correlation and downstream SMPP replay state.
 */
public interface DlrStorage {
    void saveInitialState(MessageState state);

    void linkOperatorId(String gatewayMsgId, String operatorMsgId);

    /**
     * Resolves and removes one provider correlation and its tracked message.
     * The returned state must contain the supplied status, the linked operator ID, and an updated timestamp.
     */
    Optional<MessageState> resolveAndRemoveDlr(String operatorMsgId, MessageState.MessageStatus status);

    Optional<MessageState> getState(String gatewayMsgId);

    boolean markAsFailed(String gatewayMsgId);

    boolean saveUnpushedDlr(StandardMessage message);

    List<StandardMessage> getUnpushedDlrs(String systemId);

    /**
     * Claims replayable receipts within this storage instance. V1 targets one Sendium process and does not promise
     * distributed claim coordination across multiple gateway replicas.
     */
    List<StandardMessage> claimUnpushedDlrs(String systemId);

    boolean removeUnpushedDlr(StandardMessage message);

    void releaseUnpushedDlrClaim(StandardMessage message);

}
