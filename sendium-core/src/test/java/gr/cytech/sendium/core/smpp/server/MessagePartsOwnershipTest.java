package gr.cytech.sendium.core.smpp.server;

import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.outbound.OutboundWork.SourceId;
import gr.cytech.sendium.core.storage.pending.MultipartKey;
import gr.cytech.sendium.core.storage.pending.MultipartMembership;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.time.Instant;
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

    @Test
    void acceptedGroupInstancesDoNotMergeRepeatedOrdinals() {
        try (var scheduler = new CapturingScheduler()) {
            var listener = new Listener();
            var handler = new MessagePartsHandler<>(listener, 60_000, scheduler);
            UUID firstGroup = UUID.randomUUID();
            UUID secondGroup = UUID.randomUUID();
            var first = part(1, "first");
            var repeated = part(1, "repeated");
            var second = part(2, "second");
            handler.addAcceptedPart(first, membership(first, firstGroup, 1));
            handler.addAcceptedPart(repeated, membership(repeated, secondGroup, 1));
            handler.addAcceptedPart(second, membership(second, firstGroup, 2));
            assertThat(listener.groups).hasSize(1);
            assertThat(listener.groups.getFirst()).extracting(message -> message.body).containsExactly("first", "second");
            assertThat(listener.duplicate).isNull();
            handler.new DelayedMessagePartsTask(secondGroup.toString()).call();
            assertThat(listener.groups.getLast()).containsExactly(repeated);
            handler.stop();
        }
    }

    private static MultipartMembership membership(StandardMessage part, UUID group, int ordinal) {
        SourceId source = new SourceId(UUID.randomUUID());
        part.serial = source.value().toString();
        return new MultipartMembership(source, group, MultipartKey.from(part), ordinal, Instant.now().plusSeconds(60), 0);
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

        @Override
        public void onMessagePartsHandlingEvent(MessagePartsHandler.MessagePartsEventType type, List<StandardMessage> parts) {
            events.add(type);
            groups.add(parts);
        }

        @Override
        public void onDuplicateMessagePart(StandardMessage original, StandardMessage duplicate) {
            this.original = original;
            this.duplicate = duplicate;
        }

        @Override
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
