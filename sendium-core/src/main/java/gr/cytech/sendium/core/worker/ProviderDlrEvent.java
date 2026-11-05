package gr.cytech.sendium.core.worker;

import java.util.Objects;
import java.util.Optional;

public sealed interface ProviderDlrEvent {
    String providerName();

    String providerMessageId();

    private static MessageState snapshot(MessageState source) {
        Objects.requireNonNull(source, "message");
        MessageState copy = new MessageState(source.getGatewayMsgId(), source.getAccountId(), source.getSystemId(),
                source.getSourceAddr(), source.getDestAddr(), source.getForwardDlrUrl());
        copy.setDeliveryChannel(source.getDeliveryChannel());
        copy.setDlrMask(source.getDlrMask());
        copy.setReassembledParts(source.getReassembledParts());
        return copy;
    }

    private static void requireProvider(String name, String id) {
        if (name == null || name.isBlank() || id == null || id.isBlank()) {
            throw new DlrStorageException("Invalid provider correlation");
        }
    }

    record Accepted(MessageState message, String providerName, String providerMessageId) implements ProviderDlrEvent {
        public Accepted {
            message = snapshot(message);
            requireProvider(providerName, providerMessageId);
        }
    }

    record Rejected(MessageState message, String providerName, String providerMessageId,
                    int dlrState, String errorCode) implements ProviderDlrEvent {
        public Rejected {
            message = snapshot(message);
            if (providerMessageId != null && !providerMessageId.isBlank()) {
                requireProvider(providerName, providerMessageId);
            }
        }
    }

    record Receipt(String providerName, String providerMessageId, MessageState.MessageStatus status,
                   int dlrState, String errorCode) implements ProviderDlrEvent {
        public Receipt {
            requireProvider(providerName, providerMessageId);
            Objects.requireNonNull(status, "status");
        }
    }

    record Result(Optional<MessageState> state, DlrStorageException failure) {
        public Result {
            Objects.requireNonNull(state, "state");
        }

        static Result success(Optional<MessageState> state) {
            return new Result(state, null);
        }

        static Result rejectedAcceptance() {
            return new Result(Optional.empty(), new DlrStorageException(
                    "Provider acceptance cannot replace terminal DLR state"));
        }
    }

}
