package gr.cytech.sendium.core.queue;

import gr.cytech.sendium.core.message.StandardMessage;

public interface QueueProvider {

    <T extends StandardMessage> Queue<T> subscribe(String subscriber, String queueName, boolean priorities);

    void unsubscribe(String subscriber, String queueName);

    <T extends StandardMessage> Queue<T> getRouterQueue();
}
