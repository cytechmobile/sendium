package smsgateway.routing.destination;

import org.slf4j.Logger;
import smsgateway.dto.IncomingSms;
import smsgateway.providers.LogProvider;

public class ConsoleLoggingDestination implements MessageDestination {
    private static final Logger LOGGER =
            LogProvider.getRoutingLogger(ConsoleLoggingDestination.class.getName());

    @Override
    public void process(IncomingSms message, String ruleName, String destinationId) {
        LOGGER.info(
                "ConsoleLoggingDestination - Rule: {}, Destination ID: {}. Message From: {}, To: {}, Text: {}, Timestamp: {}",
                ruleName,
                destinationId,
                message.getFrom(),
                message.getTo(),
                message.getText(),
                message.getTimestamp());
    }
}
