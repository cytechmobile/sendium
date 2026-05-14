package gr.cytech.sendium.core.queue;

import gr.cytech.sendium.core.message.StandardMessage;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

@ApplicationScoped
public class InMemoryQueueProvider implements QueueProvider {

    private final ConcurrentHashMap<String, Queue<?>> queues = new ConcurrentHashMap<>();
    private final Queue<?> routerQueue = new Queue<>(new LinkedBlockingQueue<>());

    @Override
    public <T extends StandardMessage> Queue<T> subscribe(String subscriber, String queueName, boolean priorities) {
        return (Queue<T>) queues.computeIfAbsent(queueName, k -> new Queue<>(priorities));
    }

    @Override
    public void unsubscribe(String subscriber, String queueName) {
        queues.remove(queueName);
    }

    @Override
    public <T extends StandardMessage> Queue<T> getRouterQueue() {
        return (Queue<T>) routerQueue;
    }
}