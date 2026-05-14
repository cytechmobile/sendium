package gr.cytech.sendium.core.queue;

import gr.cytech.sendium.core.message.StandardMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.TimeUnit;

public class Queue<T extends StandardMessage> {
    private static final Logger logger = LoggerFactory.getLogger(Queue.class);

    private BlockingQueue<T> queue;

    public Queue() {
        this(false);
    }

    public Queue(boolean honourPris) {
        this.queue = create(honourPris);
    }

    public Queue(BlockingQueue<T> queue) {
        this.queue = queue;
    }

    protected BlockingQueue<T> create(boolean hp) {
        return hp ?
                new PriorityBlockingQueue<>(11, Comparator.comparingInt(m -> -m.priority)) :
                new LinkedBlockingQueue<>();
    }

    public boolean setHonourPriorities(boolean honourPris) {
        if (!(this.queue instanceof PriorityBlockingQueue) && !(this.queue instanceof LinkedBlockingQueue)) {
            return false;
        }
        boolean curr = this.queue instanceof PriorityBlockingQueue;
        if (curr == honourPris) {
            return false;
        }

        BlockingQueue<T> newq = create(honourPris);
        newq.addAll(this.queue);
        this.queue = newq;

        return true;
    }

    public boolean isEmpty() {
        return queue.isEmpty();
    }

    public void enqueue(T msg) throws InterruptedException {
        logger.trace("enqueue msg:{}", msg);
        if (msg == null) {
            return;
        }
        queue.put(msg);
    }

    public T dequeue() throws InterruptedException {
        return queue.take();
    }

    public T dequeue(long timeoutInMillis) throws InterruptedException {
        return queue.poll(timeoutInMillis, TimeUnit.MILLISECONDS);
    }

    public int size() {
        return queue.size();
    }

    public boolean addAll(Collection<T> col) {
        return queue.addAll(col);
    }

    public int drainTo(Collection<T> col) {
        return queue.drainTo(col);
    }

    public int drainTo(Collection<T> col, int max) {
        return queue.drainTo(col, max);
    }

    public int drainTo(Queue<T> other) {
        return drainTo(other, Integer.MAX_VALUE);
    }

    public int drainTo(Queue<T> other, int max) {
        List<T> messages = new ArrayList<>();
        queue.drainTo(messages, max);
        other.queue.addAll(messages);
        return messages.size();
    }

    public BlockingQueue<T> getInternalQueue() {
        return queue;
    }

    @Override
    public String toString() {
        return queue.toString();
    }
}