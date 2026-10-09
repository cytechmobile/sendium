package gr.cytech.sendium.core.storage.memory;

import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.outbound.OutboundWork.Assignment;
import gr.cytech.sendium.core.outbound.OutboundWork.Destination;
import gr.cytech.sendium.core.outbound.OutboundWork.Routed;
import gr.cytech.sendium.core.outbound.OutboundWork.SelectionId;
import gr.cytech.sendium.core.outbound.OutboundWork.SourceId;
import gr.cytech.sendium.core.outbound.OutboundWork.WorkId;
import gr.cytech.sendium.core.storage.OutboundStage;
import gr.cytech.sendium.core.storage.OutboundStorageException;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

import static gr.cytech.sendium.core.storage.OutboundStorageException.Reason.CAPACITY_EXCEEDED;
import static gr.cytech.sendium.core.storage.OutboundStorageException.Reason.INVALID_TRANSITION;
import static gr.cytech.sendium.core.storage.OutboundStorageException.Reason.UNAVAILABLE;
import static gr.cytech.sendium.core.storage.OutboundStorageException.Reason.UNSUPPORTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Timeout(10)
class MemoryRoutedWorkStoreTest {
    @Test
    void recordRetriesKeepOriginalPayloadAndOneScheduledWorkItem() throws Exception {
        try (var store = store(1)) {
            var assignment = assignment("A", "original");
            assignment.destination().message().tags.add("retained");
            Routed<Payload> recorded = store.record(assignment);
            assignment.destination().message().body = "changed input";
            assignment.destination().message().tags.clear();
            recorded.message().body = "changed result";
            var replay = store.record(new Assignment<>(assignment.selection(), assignment.sources(), destination("A", "retry")));
            assertThat(replay.id()).isEqualTo(recorded.id());
            assertThat(replay.message().body).isEqualTo("original");
            assertThat(replay.message().tags).containsExactly("retained");
            replay.message().tags.clear();
            fails(() -> store.release(recorded), INVALID_TRANSITION);
            var taken = store.take("A", Duration.ZERO).orElseThrow();
            assertThat(taken.message().tags).containsExactly("retained");
            assertThat(store.take("A", Duration.ZERO)).isEmpty();
            fails(() -> store.record(assignment("B", "full")), CAPACITY_EXCEEDED);
            fails(() -> store.forget(assignment.selection()), INVALID_TRANSITION);
        }
    }

    @Test
    void conflictingAssignmentsCannotCreateCopiedDestinationsOrOverlapSources() throws Exception {
        try (var store = store(3)) {
            var assignment = assignment("A", "original");
            store.record(assignment);
            fails(() -> store.record(new Assignment<>(assignment.selection(), assignment.sources(), destination("B", "copy"))),
                    INVALID_TRANSITION);
            fails(() -> store.record(new Assignment<>(assignment.selection(), Set.of(source()), destination("A", "replacement"))),
                    INVALID_TRANSITION);
            fails(() -> store.record(new Assignment<>(selection(), assignment.sources(), destination("B", "copy"))),
                    INVALID_TRANSITION);
            assertThat(store.take("B", Duration.ZERO)).isEmpty();
            assertThat(store.take("A", Duration.ZERO)).isPresent();
            assertThat(store.take("A", Duration.ZERO)).isEmpty();
        }
    }

    @Test
    void returnPreservesExecutionStateWithoutChangingAssignmentOrOverwritingANewerTake() throws Exception {
        try (var store = store(1)) {
            var assignment = assignment("A", "original");
            store.record(assignment);
            var first = store.take("A", Duration.ZERO).orElseThrow();
            first.message().body = "retry state";
            first.message().tags.add("retry tag");
            store.release(first);
            store.release(first);
            first.message().tags.clear();
            var second = store.take("A", Duration.ZERO).orElseThrow();
            assertThat(second.id()).isEqualTo(first.id());
            assertThat(second.message().body).isEqualTo("retry state");
            assertThat(second.message().tags).containsExactly("retry tag");
            assertThat(store.record(assignment).message().body).isEqualTo("original");
            store.release(first);
            assertThat(store.take("A", Duration.ZERO)).isEmpty();
            fails(() -> store.release(new Routed<>(second.id(), second.selection(), "B", second.message())), INVALID_TRANSITION);
            store.complete(second.id());
            fails(() -> store.release(second), INVALID_TRANSITION);
        }
    }

    @Test
    void cleanupRetainsAllSourceIdsAndTerminalResultUntilForget() throws Exception {
        try (var store = store(1)) {
            var sources = Set.of(source(), source());
            var assignment = new Assignment<>(selection(), sources, destination("A", "multipart aggregate"));
            var recorded = store.record(assignment);
            fails(() -> store.complete(recorded.id()), INVALID_TRANSITION);
            var taken = store.take("A", Duration.ZERO).orElseThrow();
            fails(() -> store.forget(assignment.selection()), INVALID_TRANSITION);
            var completed = store.complete(taken.id());
            assertThat(completed.sources()).isEqualTo(sources);
            assertThat(completed.selection()).isEqualTo(assignment.selection());
            fails(() -> store.record(assignment("B", "capacity still held")), CAPACITY_EXCEEDED);
            assertThat(store.complete(taken.id())).isEqualTo(completed);
            assertThat(store.record(assignment).id()).isEqualTo(taken.id());
            assertThat(store.take("A", Duration.ZERO)).isEmpty();
            fails(() -> store.release(taken), INVALID_TRANSITION);
            store.forget(assignment.selection());
            store.forget(assignment.selection());
            fails(() -> store.complete(taken.id()), INVALID_TRANSITION);
            var replacement = new Assignment<>(selection(), sources, destination("A", "new selection"));
            store.record(replacement);
            assertThat(store.take("A", Duration.ZERO).orElseThrow().selection()).isEqualTo(replacement.selection());
        }
    }

    @Test
    void queuesAreDestinationSpecificAndPreservePublicationOrder() throws Exception {
        try (var store = store(3)) {
            store.record(assignment("A", "first"));
            store.record(assignment("B", "other"));
            store.record(assignment("A", "second"));
            assertThat(store.take("missing", Duration.ZERO)).isEmpty();
            assertThat(store.take("A", Duration.ZERO).orElseThrow().message().body).isEqualTo("first");
            assertThat(store.take("B", Duration.ZERO).orElseThrow().message().body).isEqualTo("other");
            assertThat(store.take("A", Duration.ZERO).orElseThrow().message().body).isEqualTo("second");
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void failedRecordSnapshotsPublishNothingAndLeaveCapacityAvailable(int failAt) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (var store = new MemoryRoutedWorkStore<Payload>(1, msg -> {
            if (calls.incrementAndGet() == failAt) {
                throw new IllegalStateException("snapshot failure");
            }
            return msg.copy();
        })) {
            store.open();
            var assignment = assignment("A", "payload");
            fails(() -> store.record(assignment), UNAVAILABLE);
            assertThat(store.take("A", Duration.ZERO)).isEmpty();
            var work = store.record(assignment);
            assertThat(store.take("A", Duration.ZERO).orElseThrow().id()).isEqualTo(work.id());
        }
    }

    @Test
    void failedTakeAndReturnSnapshotsRetainTheCurrentWork() throws Exception {
        AtomicBoolean enabled = new AtomicBoolean(false);
        try (var store = new MemoryRoutedWorkStore<Payload>(1, msg -> {
            if (enabled.get()) {
                throw new IllegalStateException("snapshot failure");
            }
            return msg.copy();
        })) {
            store.open();
            var recorded = store.record(assignment("A", "one"));
            enabled.set(true);
            fails(() -> store.take("A", Duration.ZERO), UNAVAILABLE);
            enabled.set(false);
            var taken = store.take("A", Duration.ZERO).orElseThrow();
            assertThat(taken.id()).isEqualTo(recorded.id());
            taken.message().body = "updated";
            enabled.set(true);
            fails(() -> store.release(taken), UNAVAILABLE);
            enabled.set(false);
            store.release(taken);
            assertThat(store.take("A", Duration.ZERO).orElseThrow().message().body).isEqualTo("updated");
            assertThat(store.take("A", Duration.ZERO)).isEmpty();
        }
    }

    @Test
    void concurrentRecordAndTakeCallsKeepOneOwner() throws Exception {
        try (var store = store(1); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var assignment = assignment("A", "one");
            var records = new ArrayList<Future<Routed<Payload>>>();
            for (int i = 0; i < 12; i++) {
                records.add(executor.submit(() -> store.record(assignment)));
            }
            Set<WorkId> ids = ConcurrentHashMap.newKeySet();
            for (var record : records) {
                ids.add(record.get(2, TimeUnit.SECONDS).id());
            }
            assertThat(ids).hasSize(1);
            var takes = new ArrayList<Future<Optional<Routed<Payload>>>>();
            for (int i = 0; i < 12; i++) {
                takes.add(executor.submit(() -> store.take("A", Duration.ZERO)));
            }
            int owners = 0;
            for (var take : takes) {
                if (take.get(2, TimeUnit.SECONDS).isPresent()) {
                    owners++;
                }
            }
            assertThat(owners).isEqualTo(1);
        }
    }

    @Test
    void racingReturnAndCompletionCannotBothSucceedForTheSameTake() throws Exception {
        try (var store = store(1); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 20; i++) {
                var assignment = assignment("A", "one");
                store.record(assignment);
                var taken = store.take("A", Duration.ZERO).orElseThrow();
                var completion = executor.submit(() -> {
                    try {
                        store.complete(taken.id());
                        return true;
                    } catch (OutboundStorageException failure) {
                        assertThat(failure.reason()).isEqualTo(INVALID_TRANSITION);
                        return false;
                    }
                });
                var retry = executor.submit(() -> {
                    try {
                        store.release(taken);
                        return true;
                    } catch (OutboundStorageException failure) {
                        assertThat(failure.reason()).isEqualTo(INVALID_TRANSITION);
                        return false;
                    }
                });
                var completed = completion.get(2, TimeUnit.SECONDS);
                var returned = retry.get(2, TimeUnit.SECONDS);
                assertThat(completed).isNotEqualTo(returned);
                if (returned) {
                    var nextTake = store.take("A", Duration.ZERO).orElseThrow();
                    assertThat(nextTake.id()).isEqualTo(taken.id());
                    store.complete(nextTake.id());
                } else {
                    assertThat(store.take("A", Duration.ZERO)).isEmpty();
                }
                store.forget(assignment.selection());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"publish", "close", "interrupt"})
    void waitingTakesWakeWithoutWaitingForTheirTimeout(String action) throws Exception {
        try (var store = store(1); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            AtomicReference<Thread> thread = new AtomicReference<>();
            var take = executor.submit(() -> {
                thread.set(Thread.currentThread());
                return store.take("A", Duration.ofSeconds(2));
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while ((thread.get() == null || thread.get().getState() != Thread.State.TIMED_WAITING) &&
                    System.nanoTime() < deadline) {
                Thread.sleep(1);
            }
            assertThat(thread.get()).isNotNull();
            assertThat(thread.get().getState()).isEqualTo(Thread.State.TIMED_WAITING);
            if (action.equals("publish")) {
                store.record(assignment("A", "one"));
                assertThat(take.get(1, TimeUnit.SECONDS)).isPresent();
            } else if (action.equals("close")) {
                store.close();
                assertThatThrownBy(() -> take.get(1, TimeUnit.SECONDS)).hasCauseInstanceOf(OutboundStorageException.class);
            } else {
                thread.get().interrupt();
                assertThatThrownBy(() -> take.get(1, TimeUnit.SECONDS)).hasCauseInstanceOf(InterruptedException.class);
            }
        }
    }

    @Test
    void lifecycleAndNewInstanceLossAreExplicit() throws Exception {
        var assignment = assignment("A", "one");
        var store = new MemoryRoutedWorkStore<Payload>(1, Payload::copy);
        assertThat(store.status()).isEqualTo(new OutboundStage.Status("memory", false, OutboundStage.State.NEW));
        fails(() -> store.record(assignment), UNAVAILABLE);
        store.open();
        store.open();
        var work = store.record(assignment);
        assertThat(store.status().durable()).isFalse();
        store.close();
        store.close();
        fails(store::open, INVALID_TRANSITION);
        fails(() -> store.complete(work.id()), UNAVAILABLE);
        try (var fresh = store(1)) {
            assertThat(fresh.take("A", Duration.ZERO)).isEmpty();
            fails(() -> fresh.complete(work.id()), INVALID_TRANSITION);
            fresh.forget(assignment.selection());
        }
    }

    @Test
    void invalidSnapshotsAndTimeoutsDoNotConsumeWork() throws Exception {
        try (var store = new MemoryRoutedWorkStore<Payload>(1, UnaryOperator.identity())) {
            store.open();
            fails(() -> store.record(assignment("A", "one")), UNSUPPORTED);
        }
        try (var store = store(1)) {
            var recorded = store.record(assignment("A", "one"));
            assertThatThrownBy(() -> store.take(" ", Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> store.take("A", Duration.ofNanos(-1))).isInstanceOf(IllegalArgumentException.class);
            assertThat(store.take("A", Duration.ofSeconds(Long.MAX_VALUE)).orElseThrow().id()).isEqualTo(recorded.id());
            assertThat(store.take("A", Duration.ofMillis(1))).isEmpty();
        }
    }

    private static MemoryRoutedWorkStore<Payload> store(int capacity) {
        var store = new MemoryRoutedWorkStore<Payload>(capacity, Payload::copy);
        store.open();
        return store;
    }

    private static Assignment<Payload> assignment(String destination, String body) {
        return new Assignment<>(selection(), Set.of(source()), destination(destination, body));
    }

    private static Destination<Payload> destination(String name, String body) {
        return new Destination<>(name, new Payload(body));
    }

    private static SourceId source() {
        return new SourceId(UUID.randomUUID());
    }

    private static SelectionId selection() {
        return new SelectionId(UUID.randomUUID());
    }

    private static void fails(ThrowingCallable operation, OutboundStorageException.Reason reason) {
        assertThatThrownBy(operation).isInstanceOfSatisfying(OutboundStorageException.class, failure -> {
            assertThat(failure.stage()).isEqualTo(OutboundStage.Role.ROUTED_WORK);
            assertThat(failure.reason()).isEqualTo(reason);
        });
    }

    private static final class Payload extends StandardMessage {
        private final List<String> tags = new ArrayList<>();

        private Payload(String body) {
            this.body = body;
        }

        private Payload copy() {
            var result = new Payload(body);
            result.tags.addAll(tags);
            return result;
        }
    }
}
