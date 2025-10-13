package smsgateway.routing.destination;

import smsgateway.dto.IncomingSms;

public interface MessageDestination {
    void process(IncomingSms message, String ruleName, String destinationId);
}
