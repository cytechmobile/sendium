package gr.cytech.sendium.core.storage.memory;

import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.outbound.OutboundWork.Assignment;
import gr.cytech.sendium.core.outbound.OutboundWork.Destination;
import gr.cytech.sendium.core.outbound.OutboundWork.Routed;
import gr.cytech.sendium.core.outbound.OutboundWork.SelectionId;
import gr.cytech.sendium.core.outbound.OutboundWork.SourceId;
import gr.cytech.sendium.core.storage.RoutedWorkStore;
import gr.cytech.sendium.core.storage.pending.PendingMessageData;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryRoutedDeferralTest {
    @Test
    void deferredWorkRotatesWithoutOwnershipLoss() throws Exception {
        try (var store = store()) {
            store.open();
            var first = store.record(assignment("a"));
            var second = store.record(assignment("b"));
            var taken = store.take("provider", Duration.ZERO).orElseThrow();
            try {
                RoutedWorkStore.class.getMethod("defer", Routed.class).invoke(store, taken);
            } catch (NoSuchMethodException missing) {
                throw new AssertionError("Memory routed storage must support explicit deferral", missing);
            }
            assertThat(store.take("provider", Duration.ZERO).orElseThrow().id()).isEqualTo(second.id());
            var deferred = store.take("provider", Duration.ZERO).orElseThrow();
            assertThat(deferred.id()).isEqualTo(first.id());
            assertThat(deferred.selection()).isEqualTo(first.selection());
            assertThat(deferred.destination()).isEqualTo(first.destination());
            assertThat(store.complete(deferred.id()).sources()).hasSize(1);
            assertThat(store.take("other", Duration.ZERO)).isEmpty();
        }
    }

    @Test
    void ordinaryReleaseRemainsIdempotent() throws Exception {
        try (var store = store()) {
            store.open();
            var original = store.record(assignment("a"));
            var taken = store.take("provider", Duration.ZERO).orElseThrow();
            taken.message().body = "updated";
            store.release(taken);
            store.release(taken);
            var returned = store.take("provider", Duration.ZERO).orElseThrow();
            assertThat(returned.id()).isEqualTo(original.id());
            assertThat(returned.message().body).isEqualTo("updated");
            assertThat(store.take("provider", Duration.ZERO)).isEmpty();
        }
    }

    private static MemoryRoutedWorkStore<StandardMessage> store() {
        return new MemoryRoutedWorkStore<>(8, message -> PendingMessageData.capture(message).restore());
    }

    private static Assignment<StandardMessage> assignment(String body) {
        var message = new StandardMessage();
        message.body = body;
        return new Assignment<>(new SelectionId(UUID.randomUUID()), Set.of(new SourceId(UUID.randomUUID())),
                new Destination<>("provider", message));
    }
}
