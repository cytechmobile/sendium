package gr.cytech.sendium.core.worker;

import java.util.List;
import java.util.Optional;

public interface DlrMessageStorage {
    void saveInitialState(MessageState state);

    default void saveInitialStates(List<MessageState> states) {
        states.forEach(this::saveInitialState);
    }

    void linkProviderMessageId(String gatewayMessageId, String providerName, String providerMessageId);

    Optional<MessageState> resolveDlr(String providerName, String providerMessageId,
                                      MessageState.MessageStatus status, int dlrState, String errorCode);

    Optional<MessageState> getState(String gatewayMsgId);

    List<MessageState> listPendingSmppDeliveries(String systemId);

    List<MessageState> listDueHttpDeliveries(int limit);

    Optional<MessageState> startDeliveryAttempt(String gatewayMsgId,
                                                MessageState.DeliveryChannel expectedChannel);

    boolean completeDelivery(String gatewayMsgId, int expectedAttempt);

    boolean retryDelivery(String gatewayMsgId, int expectedAttempt, String result, long nextAttemptAt);

    boolean failDelivery(String gatewayMsgId, int expectedAttempt, String result);

    boolean failInvalidDelivery(String gatewayMsgId, String result);
}
