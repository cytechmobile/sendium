package gr.cytech.sendium.core.storage.contract;

import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.outbound.OutboundWork.Assignment;
import gr.cytech.sendium.core.outbound.OutboundWork.Destination;
import gr.cytech.sendium.core.outbound.OutboundWork.SelectionId;
import gr.cytech.sendium.core.outbound.OutboundWork.SourceId;
import gr.cytech.sendium.core.storage.OutboundStorageException;
import gr.cytech.sendium.core.storage.PendingMessageStore;
import gr.cytech.sendium.core.storage.RoutedWorkStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;

import static gr.cytech.sendium.core.storage.OutboundStorageException.Reason.CAPACITY_EXCEEDED;
import static gr.cytech.sendium.core.storage.OutboundStorageException.Reason.INVALID_TRANSITION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Common routed-work assertions; fixtures provide accepted-source ownership for each backend. */
@Timeout(10)
abstract class RoutedWorkStoreContract {
    abstract Stores createStores(int pendingCapacity, int routedCapacity);

    @Test
    void assignmentRetryPreservesWorkIdentity() throws Exception {
        try (var stores = createStores(1, 1)) {
            var assignment = assignment(stores, 1, "A");
            var recorded = stores.routed.record(assignment);
            assertThat(stores.routed.record(assignment).id()).isEqualTo(recorded.id());
            assertThat(stores.routed.take("A", Duration.ZERO).orElseThrow().id()).isEqualTo(recorded.id());
            assertThat(stores.routed.take("A", Duration.ZERO)).isEmpty();
        }
    }

    @Test
    void assignmentRetryPreservesRecordedPayload() throws Exception {
        try (var stores = createStores(1, 1)) {
            var assignment = assignment(stores, 1, "A");
            var recorded = stores.routed.record(assignment);
            assignment.destination().message().body = "modified input";
            recorded.message().body = "modified result";
            var retried = stores.routed.record(assignment);
            assertThat(retried.message().body).isEqualTo("accepted-1");
            retried.message().body = "modified retry";
            assertThat(stores.routed.take("A", Duration.ZERO).orElseThrow().message().body).isEqualTo("accepted-1");
        }
    }

    @Test
    void conflictingDestinationFailsExplicitly() throws Exception {
        try (var stores = createStores(1, 2)) {
            var assignment = assignment(stores, 1, "A");
            var recorded = stores.routed.record(assignment);
            var conflict = new Assignment<>(assignment.selection(), assignment.sources(),
                    new Destination<>("B", assignment.destination().message()));
            assertThatThrownBy(() -> stores.routed.record(conflict))
                    .isInstanceOfSatisfying(OutboundStorageException.class,
                            failure -> assertThat(failure.reason()).isEqualTo(INVALID_TRANSITION));
            assertThat(stores.routed.take("B", Duration.ZERO)).isEmpty();
            assertThat(stores.routed.take("A", Duration.ZERO).orElseThrow().id()).isEqualTo(recorded.id());
        }
    }

    @Test
    void takeIsDestinationSpecific() throws Exception {
        try (var stores = createStores(1, 1)) {
            var recorded = stores.routed.record(assignment(stores, 1, "A"));
            assertThat(stores.routed.take("B", Duration.ZERO)).isEmpty();
            var taken = stores.routed.take("A", Duration.ZERO).orElseThrow();
            assertThat(taken.id()).isEqualTo(recorded.id());
            assertThat(taken.destination()).isEqualTo("A");
        }
    }

    @Test
    void takenWorkRetainsRecordCapacity() throws Exception {
        try (var stores = createStores(2, 1)) {
            var first = assignment(stores, 1, "A");
            stores.routed.record(first);
            var taken = stores.routed.take("A", Duration.ZERO).orElseThrow();
            var second = assignment(stores, 2, "B");
            assertThatThrownBy(() -> stores.routed.record(second))
                    .isInstanceOfSatisfying(OutboundStorageException.class,
                            failure -> assertThat(failure.reason()).isEqualTo(CAPACITY_EXCEEDED));
            assertThat(stores.pending.find(first.sources().iterator().next())).isPresent();
            stores.routed.complete(taken.id());
            stores.pending.complete(first.sources());
            stores.routed.forget(first.selection());
            stores.routed.record(second);
            assertThat(stores.routed.take("B", Duration.ZERO).orElseThrow().selection()).isEqualTo(second.selection());
        }
    }

    @Test
    void repeatedReleaseDoesNotDuplicateWork() throws Exception {
        try (var stores = createStores(1, 1)) {
            stores.routed.record(assignment(stores, 1, "A"));
            var taken = stores.routed.take("A", Duration.ZERO).orElseThrow();
            stores.routed.release(taken);
            stores.routed.release(taken);
            assertThat(stores.routed.take("A", Duration.ZERO).orElseThrow().id()).isEqualTo(taken.id());
            assertThat(stores.routed.take("A", Duration.ZERO)).isEmpty();
        }
    }

    @Test
    void repeatedCompletionPreservesCleanupOwnership() throws Exception {
        try (var stores = createStores(2, 1)) {
            var first = assignment(stores, 1, "A");
            var second = assignment(stores, 2, "A");
            var sources = Set.of(first.sources().iterator().next(), second.sources().iterator().next());
            var assignment = new Assignment<>(first.selection(), sources, first.destination());
            stores.routed.record(assignment);
            var taken = stores.routed.take("A", Duration.ZERO).orElseThrow();
            var completed = stores.routed.complete(taken.id());
            assertThat(completed.selection()).isEqualTo(assignment.selection());
            assertThat(completed.sources()).isEqualTo(sources);
            assertThat(stores.routed.complete(taken.id())).isEqualTo(completed);
            for (SourceId source : sources) {
                assertThat(stores.pending.find(source)).isPresent();
            }
        }
    }

    @Test
    void terminalRetryDoesNotReactivateWork() throws Exception {
        try (var stores = createStores(1, 1)) {
            var assignment = assignment(stores, 1, "A");
            stores.routed.record(assignment);
            var taken = stores.routed.take("A", Duration.ZERO).orElseThrow();
            stores.routed.complete(taken.id());
            assertThat(stores.routed.record(assignment).id()).isEqualTo(taken.id());
            assertThat(stores.routed.take("A", Duration.ZERO)).isEmpty();
        }
    }

    @Test
    void nonterminalForgetFailsExplicitly() throws Exception {
        try (var stores = createStores(1, 1)) {
            var assignment = assignment(stores, 1, "A");
            var recorded = stores.routed.record(assignment);
            assertThatThrownBy(() -> stores.routed.forget(assignment.selection()))
                    .isInstanceOfSatisfying(OutboundStorageException.class,
                            failure -> assertThat(failure.reason()).isEqualTo(INVALID_TRANSITION));
            assertThat(stores.routed.take("A", Duration.ZERO).orElseThrow().id()).isEqualTo(recorded.id());
        }
    }

    @Test
    void repeatedForgetPreservesUnrelatedWork() throws Exception {
        try (var stores = createStores(2, 2)) {
            var first = assignment(stores, 1, "A");
            var unrelated = assignment(stores, 2, "B");
            stores.routed.record(first);
            var retained = stores.routed.record(unrelated);
            var taken = stores.routed.take("A", Duration.ZERO).orElseThrow();
            stores.routed.complete(taken.id());
            stores.pending.complete(first.sources());
            stores.routed.forget(first.selection());
            stores.routed.forget(first.selection());
            assertThat(stores.routed.take("A", Duration.ZERO)).isEmpty();
            assertThat(stores.routed.take("B", Duration.ZERO).orElseThrow().id()).isEqualTo(retained.id());
            assertThat(stores.pending.find(unrelated.sources().iterator().next())).isPresent();
        }
    }

    private static Assignment<StandardMessage> assignment(Stores stores, int value, String destination) {
        var source = new SourceId(new UUID(0, value));
        var message = new StandardMessage();
        message.serial = source.value().toString();
        message.priority = 2;
        message.body = "accepted-" + value;
        stores.pending.admit(source, message);
        return new Assignment<>(new SelectionId(new UUID(1, value)), Set.of(source),
                new Destination<>(destination, message));
    }

    record Stores(PendingMessageStore<StandardMessage> pending,
                  RoutedWorkStore<StandardMessage> routed) implements AutoCloseable {
        Stores {
            pending.open();
            try {
                routed.open();
            } catch (RuntimeException | Error failure) {
                pending.close();
                throw failure;
            }
        }

        @Override
        public void close() {
            try {
                routed.close();
            } finally {
                pending.close();
            }
        }
    }
}
