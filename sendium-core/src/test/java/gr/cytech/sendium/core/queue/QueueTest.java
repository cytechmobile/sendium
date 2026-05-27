package gr.cytech.sendium.core.queue;

import gr.cytech.sendium.core.message.StandardMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.concurrent.SynchronousQueue;

import static org.assertj.core.api.Assertions.assertThat;

class QueueTest {

    @Test
    void defaultQueueDequeuesInFifoOrder() throws Exception {
        Queue<StandardMessage> queue = new Queue<>();
        StandardMessage first = message("first", 1);
        StandardMessage second = message("second", 3);

        queue.enqueue(first);
        queue.enqueue(second);

        assertThat(queue.dequeue(100)).isSameAs(first);
        assertThat(queue.dequeue(100)).isSameAs(second);
        assertThat(queue.isEmpty()).isTrue();
    }

    @Test
    void priorityQueueDequeuesHighestPriorityFirst() throws Exception {
        Queue<StandardMessage> queue = new Queue<>(true);
        StandardMessage low = message("low", StandardMessage.LOW_PRIORITY);
        StandardMessage high = message("high", StandardMessage.HIGH_PRIORITY);
        StandardMessage normal = message("normal", StandardMessage.NORMAL_PRIORITY);

        queue.enqueue(low);
        queue.enqueue(high);
        queue.enqueue(normal);

        assertThat(queue.dequeue(100)).isSameAs(high);
        assertThat(queue.dequeue(100)).isSameAs(normal);
        assertThat(queue.dequeue(100)).isSameAs(low);
    }

    @Test
    void setHonourPrioritiesSwitchesImplementationAndPreservesMessages() throws Exception {
        Queue<StandardMessage> queue = new Queue<>();
        StandardMessage low = message("low", StandardMessage.LOW_PRIORITY);
        StandardMessage high = message("high", StandardMessage.HIGH_PRIORITY);
        queue.enqueue(low);
        queue.enqueue(high);

        boolean changed = queue.setHonourPriorities(true);

        assertThat(changed).isTrue();
        assertThat(queue.dequeue(100)).isSameAs(high);
        assertThat(queue.dequeue(100)).isSameAs(low);
        assertThat(queue.setHonourPriorities(true)).isFalse();
    }

    @Test
    void enqueueNullDoesNotChangeQueue() throws Exception {
        Queue<StandardMessage> queue = new Queue<>();

        queue.enqueue(null);

        assertThat(queue.size()).isZero();
        assertThat(queue.dequeue(10)).isNull();
    }

    @Test
    void drainToQueueWithMaxMovesOnlyRequestedMessages() throws Exception {
        Queue<StandardMessage> source = new Queue<>();
        Queue<StandardMessage> target = new Queue<>();
        StandardMessage first = message("first", 1);
        StandardMessage second = message("second", 1);
        StandardMessage third = message("third", 1);
        source.enqueue(first);
        source.enqueue(second);
        source.enqueue(third);

        int drained = source.drainTo(target, 2);

        assertThat(drained).isEqualTo(2);
        assertThat(source.size()).isEqualTo(1);
        assertThat(target.size()).isEqualTo(2);
        assertThat(target.dequeue(100)).isSameAs(first);
        assertThat(target.dequeue(100)).isSameAs(second);
    }

    @Test
    void drainToCollectionReturnsTransferredCount() throws Exception {
        Queue<StandardMessage> queue = new Queue<>();
        queue.enqueue(message("first", 1));
        queue.enqueue(message("second", 1));
        ArrayList<StandardMessage> messages = new ArrayList<>();

        int drained = queue.drainTo(messages);

        assertThat(drained).isEqualTo(2);
        assertThat(messages).hasSize(2);
        assertThat(queue.isEmpty()).isTrue();
    }

    @Test
    void setHonourPrioritiesReturnsFalseForCustomQueueImplementation() {
        Queue<StandardMessage> queue = new Queue<>(new SynchronousQueue<>());

        boolean changed = queue.setHonourPriorities(true);

        assertThat(changed).isFalse();
    }

    private StandardMessage message(String serial, int priority) {
        StandardMessage message = new StandardMessage();
        message.serial = serial;
        message.priority = priority;
        return message;
    }
}
