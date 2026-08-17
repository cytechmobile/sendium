package gr.cytech.sendium.core.worker;

import gr.cytech.sendium.core.message.StandardMessage;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class DlrService {
    @Inject
    DlrStorage storage;

    @Inject
    ForwardDlrService forwardDlrService;

    public void saveInitialState(MessageState state) {
        storage.saveInitialState(state);
    }

    public void linkOperatorId(String gatewayMsgId, String operatorMsgId) {
        storage.linkOperatorId(gatewayMsgId, operatorMsgId);
    }

    public Optional<MessageState> resolveAndRemoveDlr(String operatorMsgId, int dlrState) {
        Optional<MessageState> state = storage.resolveAndRemoveDlr(operatorMsgId, mapDlrState(dlrState));
        state.filter(messageState -> messageState.getForwardDlrUrl() != null)
                .filter(messageState -> !messageState.getForwardDlrUrl().isEmpty())
                .ifPresent(forwardDlrService::forwardDlr);
        return state;
    }

    public Optional<MessageState> getState(String gatewayMsgId) {
        return storage.getState(gatewayMsgId);
    }

    public boolean markAsFailed(String gatewayMsgId) {
        return storage.markAsFailed(gatewayMsgId);
    }

    public boolean saveUnpushedDlr(StandardMessage message) {
        return storage.saveUnpushedDlr(message);
    }

    public List<StandardMessage> getUnpushedDlrs(String systemId) {
        return storage.getUnpushedDlrs(systemId);
    }

    public List<StandardMessage> claimUnpushedDlrs(String systemId) {
        return storage.claimUnpushedDlrs(systemId);
    }

    public boolean removeUnpushedDlr(StandardMessage message) {
        return storage.removeUnpushedDlr(message);
    }

    public void releaseUnpushedDlrClaim(StandardMessage message) {
        storage.releaseUnpushedDlrClaim(message);
    }

    private MessageState.MessageStatus mapDlrState(int dlrState) {
        return switch (dlrState) {
            case 1, 15 -> MessageState.MessageStatus.DELIVERED;
            case 5, 9 -> MessageState.MessageStatus.ACCEPTED;
            default -> MessageState.MessageStatus.FAILED;
        };
    }
}
