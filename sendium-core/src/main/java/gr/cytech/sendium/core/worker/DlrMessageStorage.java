package gr.cytech.sendium.core.worker;

import java.util.List;
import java.util.Optional;

public interface DlrMessageStorage {
    void saveInitialState(MessageState state);

    default void saveInitialStates(List<MessageState> states) {
        states.forEach(this::saveInitialState);
    }

    void linkOperatorId(String gatewayMsgId, String operatorMsgId);

    /**
     * Resolves and removes one provider correlation and its tracked message.
     * The returned state must contain the supplied status, the linked operator ID, and an updated timestamp.
     */
    Optional<MessageState> resolveAndRemoveDlr(String operatorMsgId, MessageState.MessageStatus status);

    Optional<MessageState> getState(String gatewayMsgId);

    boolean markAsFailed(String gatewayMsgId);
}
