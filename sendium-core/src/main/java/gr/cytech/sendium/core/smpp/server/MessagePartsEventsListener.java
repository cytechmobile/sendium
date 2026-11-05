package gr.cytech.sendium.core.smpp.server;

import gr.cytech.sendium.core.message.StandardMessage;

import java.util.List;

public interface MessagePartsEventsListener<M extends StandardMessage> {
    void onMessagePartsHandlingEvent(MessagePartsHandler.MessagePartsEventType type, List<M> parts);

    /** Reports an ignored duplicate ordinal without changing the existing first-part-wins policy. */
    default void onDuplicateMessagePart(M original, M duplicate) {
    }

    String getName();
}
