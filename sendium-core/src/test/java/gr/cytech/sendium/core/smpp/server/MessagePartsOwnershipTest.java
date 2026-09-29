package gr.cytech.sendium.core.smpp.server;

import gr.cytech.sendium.core.message.StandardMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MessagePartsOwnershipTest {
    @Test
    void lateCancelledTimerCannotExpireAReusedReference() throws Exception {
        try (var scheduler = new CapturingScheduler()) {
            var listener = new Listener();
            var handler = new MessagePartsHandler<>(listener, 1000, scheduler);
            handler.addMessagePart(part(1, "old-first"));
            Callable<?> oldTimer = scheduler.tasks.getFirst();
            handler.addMessagePart(part(2, "old-second"));
            assertThat(listener.events).containsExactly(MessagePartsHandler.MessagePartsEventType.COMPLETE);
            handler.addMessagePart(part(1, "new-first"));
            oldTimer.call();
            assertThat(listener.events).hasSize(1);
            handler.addMessagePart(part(2, "new-second"));
            assertThat(listener.events).containsExactly(MessagePartsHandler.MessagePartsEventType.COMPLETE,
                    MessagePartsHandler.MessagePartsEventType.COMPLETE);
            assertThat(listener.groups.getLast()).extracting(message -> message.body).containsExactly("new-first", "new-second");
            handler.stop();
        }
    }

    @Test
    void duplicateCallbackIdentifiesTheRetainedPartWithoutChangingAssembly() {
        try (var scheduler = new CapturingScheduler()) {
            var listener = new Listener();
            var handler = new MessagePartsHandler<>(listener, 1000, scheduler);
            var first = part(1, "first");
            var duplicate = part(1, "duplicate");
            handler.addMessagePart(first);
            handler.addMessagePart(duplicate);
            handler.addMessagePart(part(2, "second"));
            assertThat(listener.original).isSameAs(first);
            assertThat(listener.duplicate).isSameAs(duplicate);
            assertThat(listener.groups.getFirst()).extracting(message -> message.body).containsExactly("first", "second");
            assertThat(scheduler.tasks).hasSize(1);
            handler.stop();
        }
    }

    private static StandardMessage part(int ordinal, String body) {
        var message = new StandardMessage();
        message.owner_id = "account";
        message.binheader = "0500037F020" + ordinal;
        message.body = body;
        return message;
    }

    private static final class Listener implements MessagePartsEventsListener<StandardMessage> {
        private final List<MessagePartsHandler.MessagePartsEventType> events = new ArrayList<>();
        private final List<List<StandardMessage>> groups = new ArrayList<>();
        private StandardMessage original;
        private StandardMessage duplicate;

        public void onMessagePartsHandlingEvent(MessagePartsHandler.MessagePartsEventType type, List<StandardMessage> parts) {
            events.add(type);
            groups.add(parts);
        }

        public void onDuplicateMessagePart(StandardMessage original, StandardMessage duplicate) {
            this.original = original;
            this.duplicate = duplicate;
        }

        public String getName() {
            return "ownership-test";
        }
    }

    private static final class CapturingScheduler extends ScheduledThreadPoolExecutor {
        private final List<Callable<?>> tasks = new ArrayList<>();

        private CapturingScheduler() {
            super(1);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
            tasks.add(callable);
            ScheduledFuture<V> result = mock(ScheduledFuture.class);
            when(result.cancel(false)).thenReturn(false);
            return result;
        }
    }
}
