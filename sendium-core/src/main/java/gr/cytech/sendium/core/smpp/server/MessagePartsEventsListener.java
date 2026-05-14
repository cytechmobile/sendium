package gr.cytech.sendium.core.smpp.server;

import gr.cytech.sendium.core.message.StandardMessage;

import java.util.List;

public interface MessagePartsEventsListener<M extends StandardMessage> {
    void onMessagePartsHandlingEvent(MessagePartsHandler.MessagePartsEventType type, List<M> parts);

    String getName();
}
