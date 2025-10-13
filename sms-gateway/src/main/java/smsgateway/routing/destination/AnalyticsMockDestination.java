package smsgateway.routing.destination;

import org.slf4j.Logger;
import smsgateway.dto.IncomingSms;
import smsgateway.providers.LogProvider;

public class AnalyticsMockDestination implements MessageDestination {
    private static final Logger LOGGER =
            LogProvider.getRoutingLogger(AnalyticsMockDestination.class.getName());

    @Override
    public void process(IncomingSms message, String ruleName, String destinationId) {
        LOGGER.info(
                "AnalyticsMockDestination - Rule: {}, Destination ID: {}. Analytics Event: SMS Received - From: {}, To: {}, Text: {}, Timestamp: {}",
                ruleName,
                destinationId,
                message.getFrom(),
                message.getTo(),
                message.getText(),
                message.getTimestamp());
    }
}
