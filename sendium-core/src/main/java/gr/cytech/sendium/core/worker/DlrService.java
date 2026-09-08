package gr.cytech.sendium.core.worker;

import gr.cytech.sendium.core.message.StandardMessage;
import io.quarkus.arc.properties.IfBuildProperty;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;

@ApplicationScoped
@IfBuildProperty(name = "sendium.dlr.persistence.enabled", stringValue = "true", enableIfMissing = false)
public class DlrService {
    @Inject
    DlrStorage storage;

    public void recordProviderAccepted(MessageState state, String providerName, String providerMessageId) {
        storage.recordProviderAccepted(state, providerName, providerMessageId);
    }

    public Optional<MessageState> recordProviderRejected(MessageState state, String providerName,
                                                          String providerMessageId, int dlrState,
                                                          String errorCode) {
        return storage.recordProviderRejected(
                state, providerName, providerMessageId, dlrState, errorCode);
    }

    public Optional<MessageState> resolveDlr(String providerName, String providerMessageId, int dlrState,
                                             String errorCode) {
        if (!isTerminalDlrState(dlrState)) {
            return Optional.empty();
        }
        return storage.resolveDlr(
                providerName, providerMessageId, mapDlrState(dlrState), dlrState, errorCode);
    }

    public static boolean isTerminalDlrState(int dlrState) {
        return dlrState != StandardMessage.DLR_STAT_ACCEPTD &&
                dlrState != StandardMessage.DLR_STAT_BUFFRED;
    }

    public Optional<MessageState> getState(String gatewayMsgId) {
        return storage.getState(gatewayMsgId);
    }

    public List<MessageState> listPendingSmppDeliveries(String systemId) {
        return storage.listPendingSmppDeliveries(systemId);
    }

    public List<MessageState> listDueHttpDeliveries(int limit) {
        return storage.listDueHttpDeliveries(limit);
    }

    public Optional<MessageState> startDeliveryAttempt(String gatewayMsgId,
                                                       MessageState.DeliveryChannel expectedChannel) {
        return storage.startDeliveryAttempt(gatewayMsgId, expectedChannel);
    }

    public boolean completeDelivery(String gatewayMsgId, int expectedAttempt) {
        return storage.completeDelivery(gatewayMsgId, expectedAttempt);
    }

    public boolean retryDelivery(String gatewayMsgId, int expectedAttempt, String result, long nextAttemptAt) {
        return storage.retryDelivery(gatewayMsgId, expectedAttempt, result, nextAttemptAt);
    }

    public boolean failDelivery(String gatewayMsgId, int expectedAttempt, String result) {
        return storage.failDelivery(gatewayMsgId, expectedAttempt, result);
    }

    public boolean failInvalidDelivery(String gatewayMsgId, String result) {
        return storage.failInvalidDelivery(gatewayMsgId, result);
    }

    private MessageState.MessageStatus mapDlrState(int dlrState) {
        return switch (dlrState) {
            case StandardMessage.DLR_STAT_DELIVRD, StandardMessage.DLR_STAT_SEEN ->
                MessageState.MessageStatus.DELIVERED;
            default -> MessageState.MessageStatus.FAILED;
        };
    }
}
