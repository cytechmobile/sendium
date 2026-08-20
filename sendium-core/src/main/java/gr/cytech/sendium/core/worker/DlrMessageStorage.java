package gr.cytech.sendium.core.worker;

import java.util.List;
import java.util.Optional;

public interface DlrMessageStorage {
    void saveInitialState(MessageState state);

    default void saveInitialStates(List<MessageState> states) {
        states.forEach(this::saveInitialState);
    }

    void linkProviderMessageId(String gatewayMessageId, String providerName, String providerMessageId);

    /**
     * Resolves and removes one provider correlation and its tracked message.
     * The returned state must contain the supplied status, provider name, linked provider message ID, and an updated
     * timestamp.
     */
    Optional<MessageState> resolveAndRemoveDlr(String providerName, String providerMessageId,
                                               MessageState.MessageStatus status);

    Optional<MessageState> getState(String gatewayMsgId);

    boolean markAsFailed(String gatewayMsgId);
}
